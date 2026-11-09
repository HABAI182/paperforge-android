"""Train the original PaperForge foreground matte from procedural glyphs and curves only.

Python/Torch training is an offline developer tool; the app loads the exported ONNX.
No owner photos, proprietary outputs or external training datasets are read."""
from pathlib import Path
import torch, torch.nn as nn, numpy as np, cv2, random, time, json
import argparse

class InkNet(nn.Module):

    def __init__(self):
        super().__init__()
        self.layers = nn.Sequential(nn.Conv2d(3, 8, 3, padding=1), nn.ReLU(), nn.Conv2d(8, 8, 3, padding=2, dilation=2, groups=8), nn.Conv2d(8, 8, 1), nn.ReLU(), nn.Conv2d(8, 8, 3, padding=3, dilation=3, groups=8), nn.Conv2d(8, 8, 1), nn.ReLU(), nn.Conv2d(8, 1, 1))

    def forward(self, x):
        return self.layers(x)

def layer(rng, n):
    im = np.zeros((n, n), np.uint8)
    for k in range(rng.integers(2, 6)):
        a = (int(rng.integers(-20, n - 5)), int(rng.integers(6, n + 15)))
        text = rng.choice(['P(X>2)=1-e', 'abcXYZ123', 'sin(x)+y', '1/2 + 3/4', 'lim n->oo', 'f(x) = x^2'])
        cv2.putText(im, text, a, cv2.FONT_HERSHEY_SIMPLEX, float(rng.uniform(0.23, 0.65)), 255, int(rng.choice([1, 1, 1, 2])), cv2.LINE_AA)
    for k in range(rng.integers(0, 5)):
        pts = rng.integers(0, n, size=(rng.integers(2, 6), 2)).astype('i4')
        cv2.polylines(im, [pts], False, 255, 1, cv2.LINE_AA)
    if rng.random() < 0.3:
        y = int(rng.integers(0, n))
        cv2.line(im, (0, y), (n - 1, y), 180, 1, cv2.LINE_AA)
    return im.astype('f4') / 255

def batch(rng, b=12, n=64):
    xs = []
    ys = []
    weights = []
    for _ in range(b):
        f = layer(rng, n)
        f = cv2.GaussianBlur(f, (0, 0), float(rng.uniform(0.25, 0.65)))
        f *= rng.uniform(0.3, 1)
        back = layer(rng, n)[:, ::-1].copy()
        back = cv2.GaussianBlur(back, (0, 0), float(rng.uniform(0.45, 3.3))) * rng.uniform(0.08, 0.4)
        if rng.random() < 0.7:
            dots = (rng.random((n, n)) < rng.uniform(0.003, 0.025)).astype('f4') * rng.uniform(0.05, 0.28)
            back = np.maximum(back, cv2.GaussianBlur(dots, (0, 0), 0.5))
        ink = np.clip(f + back + rng.normal(0, 0.007, (n, n)), 0, 1).astype('f4')
        target = (f > 0.025).astype('f4')
        xs.append(np.stack([ink, cv2.GaussianBlur(ink, (0, 0), 0.8), cv2.GaussianBlur(ink, (0, 0), 2)]))
        ys.append(target[None])
        weights.append((0.15 + 5 * ((ink > 0.025) & (target == 0)) + 1.8 * target)[None])
    return (torch.from_numpy(np.array(xs)), torch.from_numpy(np.array(ys)), torch.from_numpy(np.array(weights)))

class Export(nn.Module):

    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, x):
        return self.m(x).sigmoid()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    R = args.out
    R.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(4)
    torch.manual_seed(20261102)
    np.random.seed(20261102)
    random.seed(20261102)
    model = InkNet()
    opt = torch.optim.Adam(model.parameters(), lr=0.003)
    rng = np.random.default_rng(45)
    start = time.time()
    lossfn = nn.BCEWithLogitsLoss(reduction='none')
    for step in range(7000):
        x, y, w = batch(rng)
        p = model(x)
        loss = (lossfn(p, y) * w).mean()
        opt.zero_grad()
        loss.backward()
        opt.step()
        if step % 200 == 0:
            print(step, round(float(loss), 4), round(time.time() - start, 1), flush=True)
    model.eval()
    torch.save(model.state_dict(), R / 'paper-ink-v1.pt')
    torch.save(model, R / 'paper-ink-v1-full.pt')
    val = np.random.default_rng(913)
    tp = fp = fn = 0
    with torch.no_grad():
        for _ in range(30):
            x, y, w = batch(val)
            pred = model(x).sigmoid() > 0.5
            truth = y > 0.5
            tp += int((pred & truth).sum())
            fp += int((pred & ~truth).sum())
            fn += int((~pred & truth).sum())
    report = {'seed': 20261102, 'steps': 7000, 'seconds': time.time() - start, 'parameters': sum((p.numel() for p in model.parameters())), 'validation': 'held-out synthetic seeds only, not real document accuracy', 'precision': tp / max(1, tp + fp), 'recall': tp / max(1, tp + fn), 'trainingData': 'original procedural OpenCV glyphs/curves; no owner photos/reference outputs used'}
    (R / 'paper-ink-v1-training.json').write_text(json.dumps(report, indent=2))
    print(report, flush=True)
    torch.onnx.export(Export(model), torch.zeros(1, 3, 64, 64), R / 'paper-ink-v1.onnx', input_names=['ink_features'], output_names=['foreground'], dynamic_axes={'ink_features': {2: 'height', 3: 'width'}, 'foreground': {2: 'height', 3: 'width'}}, opset_version=13, dynamo=False)
if __name__ == '__main__':
    main()
