import argparse
import json
from pathlib import Path
from typing import Any

import numpy as np
from numpy.typing import NDArray

INPUT_SIZE = 128
MIN_BLUR_SHARPNESS = 0.42
MAX_GLARE_RATIO = 0.22


def _box_blur(img: NDArray[np.float32], radius: int) -> NDArray[np.float32]:
    if radius <= 0:
        return img
    k = (2 * radius) + 1
    padded = np.pad(img, ((radius, radius), (radius, radius), (0, 0)), mode="edge")
    csum = np.cumsum(padded, axis=0)
    csum = np.concatenate([np.zeros((1, *csum.shape[1:]), dtype=np.float32), csum], axis=0)
    vert = (csum[k:] - csum[:-k]) / float(k)

    csum2 = np.cumsum(vert, axis=1)
    csum2 = np.concatenate([np.zeros((csum2.shape[0], 1, csum2.shape[2]), dtype=np.float32), csum2], axis=1)
    return ((csum2[:, k:] - csum2[:, :-k]) / float(k)).astype(np.float32)


def _compute_ground_truth_signals(img_rgb: NDArray[np.float32]) -> tuple[float, float]:
    luma = (0.299 * img_rgb[:, :, 0] + 0.587 * img_rgb[:, :, 1] + 0.114 * img_rgb[:, :, 2]) * 255.0
    glare_mask = (
        (img_rgb[:, :, 0] >= 248.0 / 255.0)
        & (img_rgb[:, :, 1] >= 248.0 / 255.0)
        & (img_rgb[:, :, 2] >= 248.0 / 255.0)
    )
    glare_ratio = float(np.mean(glare_mask))

    lap = (
        luma[:-2, 1:-1]
        + luma[2:, 1:-1]
        + luma[1:-1, :-2]
        + luma[1:-1, 2:]
        - 4.0 * luma[1:-1, 1:-1]
    )
    variance = float(np.var(lap))
    blur_score = float(variance / (variance + 320.0))
    return blur_score, glare_ratio


def generate_synthetic_label_dataset(
    n_samples: int,
    seed: int = 42,
) -> tuple[NDArray[np.float32], NDArray[np.float32]]:
    rng = np.random.default_rng(seed)
    images = np.zeros((n_samples, INPUT_SIZE, INPUT_SIZE, 3), dtype=np.float32)
    targets = np.zeros((n_samples, 7), dtype=np.float32)

    yy, xx = np.mgrid[0:INPUT_SIZE, 0:INPUT_SIZE]

    for idx in range(n_samples):
        bg_tone = rng.uniform(0.15, 0.55)
        img = np.full((INPUT_SIZE, INPUT_SIZE, 3), bg_tone, dtype=np.float32)
        img += rng.normal(0.0, 0.02, size=img.shape).astype(np.float32)

        left = float(rng.uniform(0.06, 0.24))
        top = float(rng.uniform(0.10, 0.28))
        right = float(rng.uniform(max(left + 0.45, 0.68), 0.94))
        bottom = float(rng.uniform(max(top + 0.40, 0.65), 0.92))

        x0, x1 = int(left * INPUT_SIZE), int(right * INPUT_SIZE)
        y0, y1 = int(top * INPUT_SIZE), int(bottom * INPUT_SIZE)

        label_bg = rng.uniform(0.74, 0.90, size=(3,)).astype(np.float32)
        img[y0:y1, x0:x1] = label_bg

        ink_color = rng.uniform(0.04, 0.22, size=(3,)).astype(np.float32)
        row_y = y0 + 5
        while row_y < y1 - 5:
            row_h = int(rng.integers(2, 4))
            seg_x = x0 + 4
            while seg_x < x1 - 6:
                seg_w = int(rng.integers(2, 7))
                img[row_y : min(row_y + row_h, y1 - 2), seg_x : min(seg_x + seg_w, x1 - 2)] = (
                    ink_color
                )
                seg_x += seg_w + int(rng.integers(2, 4))
            row_y += row_h + int(rng.integers(3, 6))

        mode = idx % 3
        if mode == 1:
            blur_radius = int(rng.integers(2, 5))
            img = _box_blur(img, radius=blur_radius)
        elif mode == 2:
            cx = float(rng.uniform(0.25, 0.75) * INPUT_SIZE)
            cy = float(rng.uniform(0.25, 0.75) * INPUT_SIZE)
            rad = float(rng.uniform(28.0, 56.0))
            dist2 = (xx - cx) ** 2 + (yy - cy) ** 2
            glare_spot = np.exp(-dist2 / (2.0 * (rad * 0.65) ** 2))
            img = np.clip(img + (1.35 * glare_spot[:, :, None]), 0.0, 1.0)

        img = np.clip(img, 0.0, 1.0).astype(np.float32)
        blur_score, glare_ratio = _compute_ground_truth_signals(img)
        crop_conf = float(np.clip(0.60 + 0.35 * blur_score - 0.30 * glare_ratio, 0.15, 0.98))

        images[idx] = img
        targets[idx] = np.asarray(
            [blur_score, glare_ratio, left, top, right, bottom, crop_conf],
            dtype=np.float32,
        )

    return images, targets


def train_and_export_litert_model(
    *,
    output_tflite_path: Path,
    report_output_path: Path,
    n_train: int = 1200,
    n_val: int = 300,
    epochs: int = 18,
    seed: int = 42,
) -> dict[str, Any]:
    import tensorflow as tf  # type: ignore[import-not-found]

    tf.keras.utils.set_random_seed(seed)
    x_train, y_train = generate_synthetic_label_dataset(n_train, seed=seed)
    x_val, y_val = generate_synthetic_label_dataset(n_val, seed=seed + 1000)

    inputs = tf.keras.Input(shape=(INPUT_SIZE, INPUT_SIZE, 3), dtype="float32", name="rgb_frame")
    x = tf.keras.layers.Conv2D(16, 3, strides=2, padding="same", activation="relu")(inputs)
    x = tf.keras.layers.DepthwiseConv2D(3, strides=2, padding="same", activation="relu")(x)
    x = tf.keras.layers.Conv2D(24, 1, activation="relu")(x)
    x = tf.keras.layers.DepthwiseConv2D(3, strides=2, padding="same", activation="relu")(x)
    x = tf.keras.layers.Conv2D(32, 1, activation="relu")(x)
    x = tf.keras.layers.DepthwiseConv2D(3, strides=2, padding="same", activation="relu")(x)
    x = tf.keras.layers.Conv2D(48, 1, activation="relu")(x)
    gap = tf.keras.layers.GlobalAveragePooling2D()(x)
    gmp = tf.keras.layers.GlobalMaxPooling2D()(x)
    merged = tf.keras.layers.Concatenate()([gap, gmp])
    hidden = tf.keras.layers.Dense(48, activation="relu")(merged)
    outputs = tf.keras.layers.Dense(7, activation="sigmoid", name="quality_and_crop")(hidden)

    model = tf.keras.Model(inputs=inputs, outputs=outputs, name="pillscope_label_quality_cropper")
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=2e-3),
        loss="mse",
        metrics=["mae"],
    )
    history = model.fit(
        x_train,
        y_train,
        validation_data=(x_val, y_val),
        batch_size=32,
        epochs=epochs,
        verbose=0,
    )

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_bytes: bytes = converter.convert()
    output_tflite_path.parent.mkdir(parents=True, exist_ok=True)
    output_tflite_path.write_bytes(tflite_bytes)

    interpreter = tf.lite.Interpreter(model_content=tflite_bytes)
    interpreter.allocate_tensors()
    in_detail = interpreter.get_input_details()[0]
    out_detail = interpreter.get_output_details()[0]

    preds = np.zeros_like(y_val)
    for i in range(len(x_val)):
        interpreter.set_tensor(in_detail["index"], x_val[i : i + 1])
        interpreter.invoke()
        preds[i] = interpreter.get_tensor(out_detail["index"])[0]

    mae_per_head = np.mean(np.abs(preds - y_val), axis=0)
    true_accept = (y_val[:, 0] >= MIN_BLUR_SHARPNESS) & (y_val[:, 1] <= MAX_GLARE_RATIO)
    pred_accept = (preds[:, 0] >= MIN_BLUR_SHARPNESS) & (preds[:, 1] <= MAX_GLARE_RATIO)
    accept_acc = float(np.mean(true_accept == pred_accept))

    report: dict[str, Any] = {
        "model_path": str(output_tflite_path),
        "model_size_bytes": len(tflite_bytes),
        "input_shape": [int(d) for d in in_detail["shape"]],
        "output_shape": [int(d) for d in out_detail["shape"]],
        "val_samples": int(n_val),
        "final_val_loss": round(float(history.history["val_loss"][-1]), 6),
        "tflite_blur_mae": round(float(mae_per_head[0]), 4),
        "tflite_glare_mae": round(float(mae_per_head[1]), 4),
        "tflite_bbox_mae": round(float(np.mean(mae_per_head[2:6])), 4),
        "tflite_crop_conf_mae": round(float(mae_per_head[6]), 4),
        "tflite_frame_accept_accuracy": round(accept_acc, 4),
    }
    report_output_path.parent.mkdir(parents=True, exist_ok=True)
    report_output_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Train and export LiteRT label_quality_cropper.tflite for Android CameraX."
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("android/app/src/main/assets/label_quality_cropper.tflite"),
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=Path("ml/artifacts/edge_vision_report.json"),
    )
    parser.add_argument("--train-samples", type=int, default=1200)
    parser.add_argument("--val-samples", type=int, default=300)
    parser.add_argument("--epochs", type=int, default=18)
    args = parser.parse_args()

    report = train_and_export_litert_model(
        output_tflite_path=args.output,
        report_output_path=args.report,
        n_train=args.train_samples,
        n_val=args.val_samples,
        epochs=args.epochs,
    )
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
