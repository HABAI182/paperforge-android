"""Shared pixel-coordinate sampling for synthetic and real corner refinement."""

import cv2
import numpy as np


def basis(quad, index):
    q = np.asarray(quad).reshape(4, 2)
    a = q[(index + 1) % 4] - q[index]
    b = q[(index - 1) % 4] - q[index]
    return a / max(1e-6, np.linalg.norm(a)), b / max(1e-6, np.linalg.norm(b))


def patch(image, centre, u, v, radius, size=96):
    axis = np.linspace(-radius, radius, size)
    xx, yy = np.meshgrid(axis, axis)
    points = centre + xx[:, :, None] * u + yy[:, :, None] * v
    result = cv2.remap(
        image,
        points[:, :, 0].astype("float32"),
        points[:, :, 1].astype("float32"),
        cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_REPLICATE,
    )
    return result, points
