"""Rebuild PaperForge's small DocRes ONNX asset from pinned MIT upstream sources.
Requires torch==2.15.0 (CPU is sufficient), einops, onnx, numpy. Downloads stay in
--cache, outside source control. Never loads arbitrary pickle objects.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import sys
import urllib.request

COMMIT = "d3e3a18c7c7ad10e4615ca2eb6a10f83128d8560"
SPACE = "e9d734906ea26ec569a8daeac0bfd14f495990ab"
WEIGHT_SHA = "1d6a89d754fe1e58ffd1865eab0ef3f03344798d39197b2d9a77ce4fbc8c02fd"
OUTPUT_SHA = "d11338eb9c5cb67f3c6de192fafe3b050def10465b9b89d7fe47e305dbdcaff9"

def digest(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    urls = {
        'restormer_arch.py': f'https://raw.githubusercontent.com/ZZZHANG-jx/DocRes/{COMMIT}/models/restormer_arch.py',
        'LICENSE-DOCRES': f'https://raw.githubusercontent.com/ZZZHANG-jx/DocRes/{COMMIT}/LICENSE',
        'docres.pkl': f'https://huggingface.co/spaces/qubvel-hf/documents-restoration/resolve/{SPACE}/checkpoints/docres.pkl?download=true',
    }
    for name, url in urls.items():
        target = args.cache / name
        if not target.exists():
            urllib.request.urlretrieve(url, target)
    if digest(args.cache / 'docres.pkl') != WEIGHT_SHA:
        raise SystemExit('Pinned checkpoint checksum mismatch')
    import torch
    import numpy as np
    import onnx
    from onnx import helper, numpy_helper, TensorProto
    spec = importlib.util.spec_from_file_location('paperforge_docres', args.cache / 'restormer_arch.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    model = module.Restormer(inp_channels=6, out_channels=3, dim=48, num_blocks=[2,3,3,4],
        num_refinement_blocks=4, heads=[1,2,4,8], ffn_expansion_factor=2.66,
        bias=False, LayerNorm_type='WithBias', dual_pixel_task=True)
    state = torch.load(args.cache / 'docres.pkl', map_location='cpu', weights_only=True)['model_state']
    model.load_state_dict({k.removeprefix('module.'): v for k,v in state.items()})
    model.eval()
    floating = args.cache / 'docres-128-float.onnx'
    with torch.inference_mode():
        torch.onnx.export(model, torch.zeros((1,6,128,128)), str(floating), opset_version=17,
            input_names=['image_prompt'], output_names=['restored'], dynamo=False)
    graph = onnx.load(floating)
    nodes = []
    for tensor in list(graph.graph.initializer):
        if tensor.data_type != TensorProto.FLOAT or len(tensor.dims) < 2:
            continue
        values = numpy_helper.to_array(tensor)
        scale = np.float32(max(float(np.abs(values).max()) / 127, 1e-10))
        quantized = np.clip(np.rint(values / scale), -127, 127).astype(np.int8)
        name = tensor.name
        graph.graph.initializer.remove(tensor)
        graph.graph.initializer.extend([
            numpy_helper.from_array(quantized, name+'_q'),
            numpy_helper.from_array(np.array(scale), name+'_scale'),
            numpy_helper.from_array(np.array(0,dtype=np.int8), name+'_zero'),
        ])
        nodes.append(helper.make_node('DequantizeLinear',
            [name+'_q',name+'_scale',name+'_zero'], [name], name=name+'_dequant'))
    previous = list(graph.graph.node)
    del graph.graph.node[:]
    graph.graph.node.extend(nodes + previous)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(graph, args.output)
    actual = digest(args.output)
    if actual != OUTPUT_SHA:
        raise SystemExit(f'Export differs: {actual}; inspect exporter/version before replacing the asset')
    print(json.dumps({'bytes': args.output.stat().st_size, 'sha256': actual}))

if __name__ == '__main__':
    main()
