"""Compare two ONNX hint models on local YOLO-format annotations without saving images."""
import argparse
import json
from collections import Counter
from pathlib import Path
from time import perf_counter

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageOps


CLASSES = ('visible_liquid', 'coupling_guard', 'exposed_cable', 'damaged_cooling_fins', 'ground_wire', 'discoloration')
BASELINE_CLASSES = (0, 0, 1, 2, 2, 3, 4, 5)
IMAGE_SIZE = 416


def prep_image(file):
    with Image.open(file) as source:
        image = ImageOps.exif_transpose(source).convert('RGB')
    scale = IMAGE_SIZE / max(image.size)
    width, height = (max(1, round(value * scale)) for value in image.size)
    left, top = (IMAGE_SIZE - width) // 2, (IMAGE_SIZE - height) // 2
    canvas = Image.new('RGB', (IMAGE_SIZE, IMAGE_SIZE), (114, 114, 114))
    canvas.paste(image.resize((width, height), Image.Resampling.BILINEAR), (left, top))
    tensor = np.asarray(canvas, dtype=np.float32).transpose(2, 0, 1)[None] / 255
    return tensor, (width, height, left, top)


def annotations(file):
    rows = [line.split() for line in file.read_text(encoding='utf-8').splitlines() if line.strip()]
    return [(int(kind), (x - w / 2, y - h / 2, x + w / 2, y + h / 2))
            for kind, x, y, w, h in [tuple(map(float, row)) for row in rows]]


def overlap(first, second):
    area = lambda box: max(0, box[2] - box[0]) * max(0, box[3] - box[1])
    shared = area((max(first[0], second[0]), max(first[1], second[1]),
                   min(first[2], second[2]), min(first[3], second[3])))
    return shared / max(1e-9, area(first) + area(second) - shared)


def evaluate(model, items, confidence, min_iou):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(model), options, providers=['CPUExecutionProvider'])
    shape = session.get_outputs()[0].shape
    if shape not in ([1, 300, 6], [1, 300, 38]):
        raise ValueError(f'Incompatible detection shape {shape}')
    baseline = shape[-1] == 38
    stats = [Counter(positive=0, negative=0, tp=0, fp=0, fn=0) for _ in CLASSES]
    times = []
    for image, dimensions, expected in items:
        width, height, left, top = dimensions
        start = perf_counter()
        prediction = session.run(['output0'], {'images': image})[0][0]
        times.append((perf_counter() - start) * 1e3)
        candidates = {}
        for row in prediction:
            x0, y0, x1, y1, score, category = row[:6]
            if not np.all(np.isfinite(row[:6])) or score < confidence or x1 <= x0 or y1 <= y0:
                continue
            kind = BASELINE_CLASSES[int(category)] if baseline else int(category)
            coords = np.clip(((x0-left)/width, (y0-top)/height, (x1-left)/width, (y1-top)/height), 0, 1)
            if kind not in candidates or score > candidates[kind][0]:
                candidates[kind] = (score, coords)
        for category, scores in enumerate(stats):
            boxes = [box for kind, box in expected if kind == category]
            scores['positive' if boxes else 'negative'] += 1
            candidate = candidates.get(category)
            matched = bool(candidate and any(overlap(candidate[1], box) >= min_iou for box in boxes))
            scores['tp'] += int(matched)
            scores['fp'] += int(candidate is not None and not matched)
            scores['fn'] += int(bool(boxes) and not matched)
    return {'images': len(items), 'median_inference_ms': round(float(np.median(times)), 1),
            'classes': dict(zip(CLASSES, stats))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('dataset', type=Path)
    parser.add_argument('models', nargs='+', type=Path)
    parser.add_argument('--split', default='holdout')
    parser.add_argument('--confidence', type=float, default=0.35)
    parser.add_argument('--iou', type=float, default=0.5)
    args = parser.parse_args()
    photos = sorted((args.dataset / 'images' / args.split).glob('*.jpg'))
    if not photos:
        parser.error(f'No JPEG images in {args.split}')
    items = [(*prep_image(photo), annotations(args.dataset / 'labels' / args.split / f'{photo.stem}.txt')) for photo in photos]
    result = [{'model': model.name, **evaluate(model, items, args.confidence, args.iou)} for model in args.models]
    print(json.dumps({'split': args.split, 'confidence': args.confidence, 'iou': args.iou, 'models': result}, indent=2))


if __name__ == '__main__':
    main()
