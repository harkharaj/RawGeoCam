#!/usr/bin/env python3
"""
Read a RawGeoCam capture (IMG_xxx.dng + IMG_xxx.json) with the image, intrinsics, distortion and
pose all expressed in the same frame.

    from rawgeocam import Capture
    cap = Capture("IMG_20261112_154800_123.json")
    rgb, K, dist = cap.image(frame="display")      # needs: pip install rawpy
    p = cap.pose(frame="display")                    # R_world_from_camera (ENU), heading, pitch, roll, lat/lon
    mosaic, K_raw, dist = cap.raw_mosaic()           # the untouched Bayer data, sensor frame

Conventions (see README.md):
  * Pixel coordinates are OpenCV style: the centre of pixel (u, v) is at (u, v).
  * Camera frame: x = increasing column, y = increasing row, z = optical axis into the scene.
  * World frame: ENU (east, north, up); north is true north when the phone had a location fix.
  * frame="sensor": the DNG pixel grid as stored (what the Camera2 intrinsics refer to).
    frame="display": rotated by the DNG orientation tag so the image is upright.

Only numpy is required, plus rawpy for image()/raw_mosaic().
"""
import json
import os
import sys
import warnings

import numpy as np


# ---------------------------------------------------------------- geometry helpers

def rotate_intrinsics(K, dist, size, rot_cw_deg):
    """Intrinsics/distortion of the same camera after rotating the image clockwise by
    rot_cw_deg (a multiple of 90), e.g. with np.rot90(img, k=-rot_cw_deg // 90).
    size = (width, height) of the unrotated image. Returns (K, dist, (width, height))."""
    K = np.asarray(K, dtype=float).copy()
    dist = np.asarray(dist, dtype=float).copy()
    w, h = size
    for _ in range((int(rot_cw_deg) // 90) % 4):
        A = np.array([[0.0, -1.0, h - 1.0], [1.0, 0.0, 0.0], [0.0, 0.0, 1.0]])  # pixel (u,v) -> (h-1-v, u)
        B = np.array([[0.0, -1.0, 0.0], [1.0, 0.0, 0.0], [0.0, 0.0, 1.0]])      # normalised (x,y) -> (-y, x)
        K = A @ K @ np.linalg.inv(B)
        k1, k2, p1, p2, k3 = dist
        dist = np.array([k1, k2, p2, -p1, k3])
        w, h = h, w
    K[np.abs(K) < 1e-12] = 0.0
    return K, dist, (w, h)


def sensor_cam_from_display_cam(rot_cw_deg):
    """R_sensorCam_from_displayCam for an image rotated clockwise by rot_cw_deg (matches the app)."""
    t = np.deg2rad(rot_cw_deg)
    c, s = round(np.cos(t)), round(np.sin(t))
    return np.array([[c, s, 0.0], [-s, c, 0.0], [0.0, 0.0, 1.0]])


def angles_from_R(R):
    """(heading, pitch, roll) in degrees from R_world_from_camera (ENU), as defined in the README."""
    R = np.asarray(R, dtype=float)
    x, d = R[:, 0], R[:, 2]
    heading = np.degrees(np.arctan2(d[0], d[1])) % 360.0
    pitch = np.degrees(np.arcsin(np.clip(d[2], -1, 1)))
    hn = np.hypot(d[0], d[1])
    if hn < 1e-9:
        return heading, pitch, 0.0
    right = np.array([d[1] / hn, -d[0] / hn, 0.0])
    down = np.cross(d, right)
    roll = np.degrees(np.arctan2(x @ down, x @ right))
    return heading, pitch, (roll + 180.0) % 360.0 - 180.0


def R_from_angles(heading, pitch, roll):
    """Inverse of angles_from_R."""
    h, p, r = np.radians([heading, pitch, roll])
    d = np.array([np.cos(p) * np.sin(h), np.cos(p) * np.cos(h), np.sin(p)])
    right = np.array([np.cos(h), -np.sin(h), 0.0])
    down = np.cross(d, right)
    x = np.cos(r) * right + np.sin(r) * down
    y = -np.sin(r) * right + np.cos(r) * down
    return np.column_stack([x, y, d])


def project(points_cam, K, dist):
    """OpenCV-model projection of Nx3 camera-frame points (same maths as cv2.projectPoints)."""
    P = np.asarray(points_cam, dtype=float)
    x, y = P[:, 0] / P[:, 2], P[:, 1] / P[:, 2]
    k1, k2, p1, p2, k3 = dist
    r2 = x * x + y * y
    radial = 1 + k1 * r2 + k2 * r2 ** 2 + k3 * r2 ** 3
    xd = x * radial + 2 * p1 * x * y + p2 * (r2 + 2 * x * x)
    yd = y * radial + p1 * (r2 + 2 * y * y) + 2 * p2 * x * y
    uv1 = (np.asarray(K) @ np.vstack([xd, yd, np.ones_like(xd)])).T
    return uv1[:, :2] / uv1[:, 2:3]


def ecef_from_enu_rotation(lat_deg, lon_deg):
    """R_ecef_from_enu at a geodetic latitude/longitude (columns are E, N, U in ECEF)."""
    la, lo = np.radians([lat_deg, lon_deg])
    e = [-np.sin(lo), np.cos(lo), 0.0]
    n = [-np.sin(la) * np.cos(lo), -np.sin(la) * np.sin(lo), np.cos(la)]
    u = [np.cos(la) * np.cos(lo), np.cos(la) * np.sin(lo), np.sin(la)]
    return np.column_stack([e, n, u])


# ---------------------------------------------------------------- capture reader

class Capture:
    def __init__(self, json_path):
        self.json_path = os.path.abspath(json_path)
        with open(self.json_path, encoding="utf-8") as f:
            self.meta = json.load(f)
        self.image_path = os.path.join(os.path.dirname(self.json_path), self.meta["image_file"])
        self.is_raw = self.image_path.lower().endswith(".dng")

    @property
    def display_rotation(self):
        return int(self.meta["image"]["display_rotation_cw_deg"])

    def _dist(self):
        d = self.meta["intrinsics"]["distortion_opencv_k1_k2_p1_p2_k3"]
        if d is None:
            warnings.warn(f"{os.path.basename(self.json_path)}: the phone published no distortion for this lens; "
                          "using zeros (a perfect pinhole), which is wrong for real lenses, especially ultrawides. "
                          "Calibrate with tools/verify_intrinsics.py.", stacklevel=3)
            return np.zeros(5)
        return np.asarray(d, dtype=float)

    @property
    def quality_ok(self):
        """False when the app flagged anything (stale/poor GPS, low compass calibration, motion, ...)."""
        return bool(self.meta.get("summary", {}).get("quality_ok", True))

    @property
    def quality_issues(self):
        return list(self.meta.get("summary", {}).get("quality_issues", []))

    def intrinsics(self, frame="sensor"):
        """(K, dist, (width, height)) for the active-area image (rawpy raw_image_visible /
        postprocess(user_flip=0)); for frame='display' rotated upright. JPEG fallback: the JPEG itself."""
        intr = self.meta["intrinsics"]
        if self.is_raw:
            K = np.asarray(intr["K_opencv_active_area"], dtype=float)
            size = tuple(intr["K_opencv_active_area_size"])
        else:
            K = np.asarray(intr["K_opencv_image"], dtype=float)
            size = tuple(intr["K_opencv_image_size"])
        dist = self._dist()
        if frame == "display":
            return rotate_intrinsics(K, dist, size, self.display_rotation)
        if frame != "sensor":
            raise ValueError("frame must be 'sensor' or 'display'")
        return K, dist, size

    def pose(self, frame="sensor"):
        """Camera orientation and position. R maps camera-frame vectors into the ENU world frame."""
        o = self.meta["orientation"]
        key = "sensor_frame" if frame == "sensor" else "display_frame"
        if key not in o:
            raise ValueError("capture has no orientation data")
        f = o[key]
        loc = self.meta.get("location") or {}
        return {
            "R_world_from_camera": np.asarray(f["R_world_from_camera"], dtype=float),
            "heading_deg": f["heading_deg"],
            "pitch_deg": f["pitch_deg"],
            "roll_deg": f["roll_deg"],
            "world_frame": o["world_frame"],
            "latitude": loc.get("latitude"),
            "longitude": loc.get("longitude"),
            "altitude_wgs84_m": loc.get("altitude_wgs84_ellipsoid_m"),
            "horizontal_accuracy_m": loc.get("horizontal_accuracy_m"),
        }

    def raw_mosaic(self):
        """(bayer, K, dist): the unprocessed active-area mosaic (uint16, sensor frame) and its intrinsics."""
        import rawpy
        with rawpy.imread(self.image_path) as raw:
            mosaic = raw.raw_image_visible.copy()
        K, dist, size = self.intrinsics("sensor")
        if (mosaic.shape[1], mosaic.shape[0]) != tuple(size):
            raise RuntimeError(f"visible raw is {mosaic.shape[::-1]}, expected {size}; see README 'Pixel grids'")
        return mosaic, K, dist

    def image(self, frame="sensor", **postprocess_kwargs):
        """(rgb, K, dist): demosaiced image from rawpy with matching intrinsics. Extra keyword arguments
        go to rawpy's postprocess (e.g. output_bps=16, use_camera_wb=True, no_auto_bright=True)."""
        if not self.is_raw:
            raise RuntimeError("JPEG fallback capture: open the .jpg directly and use intrinsics()")
        import rawpy
        kw = dict(use_camera_wb=True)
        kw.update(postprocess_kwargs)
        kw["user_flip"] = 0  # always render in the sensor grid; rotation is applied below
        with rawpy.imread(self.image_path) as raw:
            rgb = raw.postprocess(**kw)
        K, dist, size = self.intrinsics("sensor")
        got = (rgb.shape[1], rgb.shape[0])
        if got != tuple(size):
            crop = tuple(self.meta["intrinsics"]["K_opencv_dng_default_crop_size"])
            if got == crop:  # renderer applied the DNG DefaultCrop
                K = np.asarray(self.meta["intrinsics"]["K_opencv_dng_default_crop"], dtype=float)
                size = crop
            else:
                raise RuntimeError(f"rendered image is {got}, expected {size} or {crop}; see README 'Pixel grids'")
        if frame == "display":
            rot = self.display_rotation
            K, dist, size = rotate_intrinsics(K, dist, size, rot)
            rgb = np.rot90(rgb, k=-(rot // 90))
        return np.ascontiguousarray(rgb), K, dist


# ---------------------------------------------------------------- self-test

def selftest(seed=0):
    rng = np.random.default_rng(seed)
    K = np.array([[3000.0, 0.0, 2010.3], [0.0, 3002.0, 1490.7], [0.0, 0.0, 1.0]])
    dist = np.array([0.08, -0.21, 0.0012, -0.0007, 0.11])
    size = (4032, 3024)
    pts = np.column_stack([rng.uniform(-1, 1, 500), rng.uniform(-0.75, 0.75, 500), np.ones(500)]) * rng.uniform(2, 50, (500, 1))
    uv = project(pts, K, dist)
    for rot in (0, 90, 180, 270):
        K2, d2, size2 = rotate_intrinsics(K, dist, size, rot)
        uv2 = project(pts @ sensor_cam_from_display_cam(rot), K2, d2)  # points in display-camera coords
        # where np.rot90(img, -rot//90) moves each sensor pixel
        u, v = uv[:, 0], uv[:, 1]
        w, h = size
        expected = {0: (u, v), 90: (h - 1 - v, u), 180: (w - 1 - u, h - 1 - v), 270: (v, w - 1 - u)}[rot]
        err = np.abs(uv2 - np.column_stack(expected)).max()
        assert err < 1e-6, (rot, err)
        assert size2 == (size if rot % 180 == 0 else size[::-1])
    # the pixel mapping above really is np.rot90's: check on a small array
    img = np.arange(12).reshape(3, 4)
    r = np.rot90(img, k=-1)
    assert r[1, 3 - 1 - 2] == img[2, 1]  # sensor (u=1, v=2) -> display (u=h-1-v=0, v=u=1)
    for _ in range(1000):
        h, p, ro = rng.uniform(0, 360), rng.uniform(-80, 80), rng.uniform(-180, 180)
        h2, p2, r2 = angles_from_R(R_from_angles(h, p, ro))
        assert abs((h2 - h + 180) % 360 - 180) < 1e-7 and abs(p2 - p) < 1e-7 and abs((r2 - ro + 180) % 360 - 180) < 1e-7
    print("selftest passed")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] != "--selftest":
        cap = Capture(sys.argv[1])
        K, dist, size = cap.intrinsics("display")
        p = cap.pose("display")
        np.set_printoptions(suppress=True, precision=4)
        print(f"{cap.meta['image_file']}  ({size[0]}x{size[1]} upright)  lens: {cap.meta.get('summary', {}).get('lens', '?')}")
        print("quality:", "OK" if cap.quality_ok else "ISSUES " + ", ".join(cap.quality_issues))
        for w in cap.meta["intrinsics"].get("checks", {}).get("warnings", []):
            print("intrinsics check:", w)
        print(f"lat/lon  {p['latitude']}, {p['longitude']}  ±{p['horizontal_accuracy_m']} m")
        print(f"heading {p['heading_deg']:.2f}  pitch {p['pitch_deg']:.2f}  roll {p['roll_deg']:.2f}   [{p['world_frame']}]")
        print("K =\n", K, "\ndist =", dist)
        print("R_world_from_camera =\n", p["R_world_from_camera"])
    else:
        selftest()
