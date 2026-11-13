"""Where does train_page_models.py spend its time? Measures each stage on its own so a change can be judged by
steps per second, not by GPU utilisation.

  loader   batches/s from train_page_models.batches (threads, memmap reads, flips, edge maps)
  step     training steps/s on a fixed GPU batch, per architecture, for several settings
           (fp16 autocast as today; + cudnn.benchmark; + channels_last; bf16; no per-step loss.item())
  eval     seconds for one evaluate() call

usage: profile_page_training.py --scenes <dirs...> --public <public-data.npz> [--archs pagenet mbv3unet liteseg]
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import torch

import train_page_models as tm


def timed_loader(stream, n=60):
    next(stream)  # warm the queue
    t = time.perf_counter()
    for _ in range(n):
        next(stream)
    return n / (time.perf_counter() - t)


def timed_steps(arch, x, m, e, *, benchmark, channels_last, dtype, sync_each, compile=False, n=150):
    torch.backends.cudnn.benchmark = benchmark
    torch.manual_seed(7)
    model = tm.build(arch).cuda()
    if channels_last:
        model = model.to(memory_format=torch.channels_last)
        x = x.contiguous(memory_format=torch.channels_last)
    opt = torch.optim.AdamW(model.parameters(), lr=1e-3)
    net = torch.compile(model) if compile else model
    scaler = torch.amp.GradScaler("cuda", enabled=dtype == torch.float16)

    def step():
        opt.zero_grad(set_to_none=True)
        with torch.amp.autocast("cuda", dtype=dtype):
            loss = tm.loss_fn(net(x), m, e)
        scaler.scale(loss).backward()
        scaler.step(opt)
        scaler.update()
        return loss

    for _ in range(20):
        step()
    torch.cuda.synchronize()
    torch.cuda.reset_peak_memory_stats()
    t = time.perf_counter()
    total = torch.zeros((), device="cuda")
    for _ in range(n):
        loss = step()
        if sync_each:
            loss.item()
        else:
            total += loss.detach()
    torch.cuda.synchronize()
    return n / (time.perf_counter() - t), torch.cuda.max_memory_allocated() / 2**20


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--scenes", type=Path, nargs="+", required=True)
    ap.add_argument("--public", type=Path, required=True)
    ap.add_argument("--archs", nargs="+", default=["pagenet", "mbv3unet", "liteseg"])
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--only", nargs="*", help="run only settings whose name contains one of these")
    ap.add_argument("--skip-loader-eval", action="store_true")
    args = ap.parse_args()
    sets = tm.scene_sets(args.scenes)
    public = tm.public_split(args.public, args.public.with_name("split.json"), 100)
    out = {}
    stream = tm.batches(sets, 1000, public, (0.25, 0.4), args.batch, 11)
    if not args.skip_loader_eval:
        out["loaderBatchesPerSecond"] = round(timed_loader(stream), 2)
    print(json.dumps(out), flush=True)
    x, m, e = next(stream)
    x = x.cuda().float() / 255
    m, e = m.cuda().float(), e.cuda().float()
    settings = {
        "current (fp16, item each step)": dict(benchmark=False, channels_last=False, dtype=torch.float16, sync_each=True),
        "+ no per-step sync": dict(benchmark=False, channels_last=False, dtype=torch.float16, sync_each=False),
        "+ cudnn.benchmark": dict(benchmark=True, channels_last=False, dtype=torch.float16, sync_each=False),
        "+ channels_last": dict(benchmark=True, channels_last=True, dtype=torch.float16, sync_each=False),
        "bf16 + benchmark + channels_last": dict(benchmark=True, channels_last=True, dtype=torch.bfloat16, sync_each=False),
        "bf16 + benchmark (NCHW)": dict(benchmark=True, channels_last=False, dtype=torch.bfloat16, sync_each=False),
        "bf16 + channels_last + compile": dict(benchmark=True, channels_last=True, dtype=torch.bfloat16, sync_each=False, compile=True),
    }
    if args.only:
        settings = {k: v for k, v in settings.items() if any(o in k for o in args.only)}
    for arch in args.archs:
        out[arch] = {}
        for name, s in settings.items():
            sps, mem = timed_steps(arch, x, m, e, **s)
            out[arch][name] = {"stepsPerSecond": round(sps, 1), "peakMB": round(mem)}
            print(arch, name, out[arch][name], flush=True)
    if args.skip_loader_eval:
        print(json.dumps(out, indent=1))
        return
    torch.backends.cudnn.benchmark = False
    realistic = [s for s in sets if s[0].startswith("scenes-real")] or sets[:1]
    synth_val = (np.concatenate([np.array(s[2][s[1] - 1000:]) for s in realistic]),
                 np.concatenate([np.array(s[3][s[1] - 1000:]) for s in realistic]))
    model = tm.build("pagenet").cuda()
    t = time.perf_counter()
    tm.evaluate(model, "cuda", public, synth_val)
    out["evaluateSeconds"] = round(time.perf_counter() - t, 1)
    out["evaluateImages"] = int(len(public[4]) + len(synth_val[0]))
    print(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
