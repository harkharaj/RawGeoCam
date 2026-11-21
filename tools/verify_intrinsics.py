#!/usr/bin/env python3
"""
Check the phone's factory intrinsics against your own checkerboard calibration.

Shoot 15-30 RawGeoCam captures of a printed checkerboard with ONE lens, ONE focus setting (manual mode,
same focus value) and the board filling different parts of the frame (corners and edges matter most,
tilted views help). Then:

    pip install numpy rawpy opencv-python
    python tools/verify_intrinsics.py captures/IMG_*.json --board 9x6 --square 0.025

--board is the number of INNER corners (columns x rows); --square is the square size in metres (only
affects the reported board poses, not the intrinsics).

It prints, and writes to verify_intrinsics_report.json:
  * your calibration (K, distortion, RMS reprojection error),
  * how well the PHONE's K + distortion explain the same corners (pose solved per image, intrinsics
    held fixed): if this RMS is close to your calibration's, the factory values are good,
  * the largest pixel disagreement between the two models anywhere in the frame.

Everything is in the sensor pixel grid of the DNG active area (OpenCV convention), the same frame as
intrinsics.K_opencv_active_area in the JSON.
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


# ---------------------------------------------------------------- core (no file I/O, unit-testable)

def board_points(cols, rows, square):
    obj = np.zeros((rows * cols, 3), np.float32)
    obj[:, :2] = np.mgrid[0:cols, 0:rows].T.reshape(-1, 2) * square
    return obj


def detect_corners(gray, pattern, max_side=2000):
    """Inner corners of a checkerboard in an 8-bit grayscale image, sub-pixel refined at full resolution.
    Returns an (N, 1, 2) float32 array or None."""
    import cv2
    h, w = gray.shape[:2]
    scale = min(1.0, max_side / max(h, w))
    small = cv2.resize(gray, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA) if scale < 1 else gray
    found, corners = cv2.findChessboardCornersSB(small, pattern, flags=cv2.CALIB_CB_EXHAUSTIVE | cv2.CALIB_CB_ACCURACY)
    if not found:
        found, corners = cv2.findChessboardCorners(small, pattern, flags=cv2.CALIB_CB_ADAPTIVE_THRESH | cv2.CALIB_CB_NORMALIZE_IMAGE)
        if not found:
            return None
    corners = (corners.reshape(-1, 1, 2) / scale).astype(np.float32)
    win = max(5, int(round(0.4 * square_size_px(corners, pattern))))
    criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 100, 1e-4)
    cv2.cornerSubPix(gray, corners, (win, win), (-1, -1), criteria)
    return corners


def square_size_px(corners, pattern):
    c = corners.reshape(pattern[1], pattern[0], 2)
    return float(np.median(np.linalg.norm(np.diff(c, axis=1), axis=2)))


def calibrate(obj_list, img_list, size, fix_k3=False, K_guess=None):
    """Free OpenCV calibration (pinhole + k1 k2 p1 p2 k3). Returns (rms, K, dist, per_view_rms)."""
    import cv2
    flags = cv2.CALIB_FIX_K3 if fix_k3 else 0
    K0 = None
    if K_guess is not None:
        K0 = np.asarray(K_guess, dtype=np.float64).copy()
        flags |= cv2.CALIB_USE_INTRINSIC_GUESS
    rms, K, dist, rvecs, tvecs = cv2.calibrateCamera(obj_list, img_list, size, K0, None, flags=flags)
    per_view = []
    for o, i, r, t in zip(obj_list, img_list, rvecs, tvecs):
        p, _ = cv2.projectPoints(o, r, t, K, dist)
        per_view.append(float(np.sqrt(np.mean(np.sum((p.reshape(-1, 2) - i.reshape(-1, 2)) ** 2, axis=1)))))
    return float(rms), K, dist.ravel()[:5], per_view


def rms_with_fixed_intrinsics(K, dist, obj_list, img_list):
    """Reprojection RMS when the intrinsics are held fixed and only each board pose is solved."""
    import cv2
    sq, n, per_view = 0.0, 0, []
    for o, i in zip(obj_list, img_list):
        ok, r, t = cv2.solvePnP(o, i, K, dist, flags=cv2.SOLVEPNP_ITERATIVE)
        if not ok:
            per_view.append(float("nan"))
            continue
        r, t = cv2.solvePnPRefineLM(o, i, K, dist, r, t)
        p, _ = cv2.projectPoints(o, r, t, K, dist)
        e = np.sum((p.reshape(-1, 2) - i.reshape(-1, 2)) ** 2, axis=1)
        sq += float(e.sum())
        n += len(e)
        per_view.append(float(np.sqrt(e.mean())))
    return (float(np.sqrt(sq / n)) if n else float("nan")), per_view


def model_disagreement(K1, d1, K2, d2, size, step=16):
    """For pixels on a grid over the image: take the viewing ray under model 1 and project it with model 2.
    Returns (max, median, 95th percentile) pixel displacement."""
    import cv2
    w, h = size
    u, v = np.meshgrid(np.arange(0, w, step, dtype=np.float64), np.arange(0, h, step, dtype=np.float64))
    pts = np.stack([u.ravel(), v.ravel()], axis=1).reshape(-1, 1, 2)
    rays = cv2.undistortPoints(pts, K1, d1, criteria=(cv2.TERM_CRITERIA_COUNT | cv2.TERM_CRITERIA_EPS, 50, 1e-10)).reshape(-1, 2)
    xyz = np.hstack([rays, np.ones((len(rays), 1))])
    p2, _ = cv2.projectPoints(xyz, np.zeros(3), np.zeros(3), K2, d2)
    disp = np.linalg.norm(p2.reshape(-1, 2) - pts.reshape(-1, 2), axis=1)
    return float(disp.max()), float(np.median(disp)), float(np.percentile(disp, 95))


def coverage(img_list, size, grid=4):
    """Fraction of a grid x grid tiling of the frame that contains at least one detected corner."""
    w, h = size
    hit = np.zeros((grid, grid), bool)
    for i in img_list:
        p = i.reshape(-1, 2)
        gx = np.clip((p[:, 0] / w * grid).astype(int), 0, grid - 1)
        gy = np.clip((p[:, 1] / h * grid).astype(int), 0, grid - 1)
        hit[gy, gx] = True
    return float(hit.mean())


def calibrate_robust(obj_list, img_list, size, fix_k3=False, K_guess=None):
    """calibrate(), then drop views whose error is far above the rest (mis-detected or blurred boards)
    and recalibrate. Returns (rms, K, dist, per_view_rms, kept_indices, dropped_indices)."""
    keep = list(range(len(obj_list)))
    dropped = []
    rms, K, dist, per_view = calibrate(obj_list, img_list, size, fix_k3, K_guess)
    for _ in range(3):
        limit = max(3.0 * float(np.median(per_view)), 1.0)
        bad = [i for i, e in zip(keep, per_view) if e > limit]
        if not bad or len(keep) - len(bad) < 3:
            break
        dropped += bad
        keep = [i for i in keep if i not in bad]
        rms, K, dist, per_view = calibrate([obj_list[i] for i in keep], [img_list[i] for i in keep], size, fix_k3, K_guess)
    return rms, K, dist, per_view, keep, dropped


def compare(K_phone, d_phone, obj_list, img_list, size, fix_k3=False):
    """Everything the report needs, from detected corners. d_phone may be None (not published)."""
    rms_cal, K_cal, d_cal, per_view_cal, keep, dropped = calibrate_robust(
        obj_list, img_list, size, fix_k3=fix_k3, K_guess=K_phone)
    obj_list = [obj_list[i] for i in keep]
    img_list = [img_list[i] for i in keep]
    d_ph = np.zeros(5) if d_phone is None else np.asarray(d_phone, dtype=np.float64)
    rms_phone, per_view_phone = rms_with_fixed_intrinsics(K_phone, d_ph, obj_list, img_list)
    dmax, dmed, d95 = model_disagreement(K_phone, d_ph, K_cal, d_cal, size)
    return {
        "image_size": list(size),
        "views": len(obj_list),
        "kept_view_indices": keep,
        "dropped_view_indices": dropped,
        "frame_coverage_4x4": coverage(img_list, size),
        "calibrated": {"K": K_cal.tolist(), "dist_k1_k2_p1_p2_k3": d_cal.tolist(), "rms_px": rms_cal,
                       "per_view_rms_px": per_view_cal},
        "phone": {"K": np.asarray(K_phone).tolist(), "dist_k1_k2_p1_p2_k3": None if d_phone is None else d_ph.tolist(),
                  "rms_px_with_intrinsics_fixed": rms_phone, "per_view_rms_px": per_view_phone},
        "difference": {
            "fx_percent": 100.0 * (K_phone[0][0] - K_cal[0, 0]) / K_cal[0, 0],
            "fy_percent": 100.0 * (K_phone[1][1] - K_cal[1, 1]) / K_cal[1, 1],
            "cx_px": float(K_phone[0][2] - K_cal[0, 2]),
            "cy_px": float(K_phone[1][2] - K_cal[1, 2]),
            "model_disagreement_px": {"max": dmax, "median": dmed, "p95": d95},
        },
    }


def verdict(r):
    rc, rp = r["calibrated"]["rms_px"], r["phone"]["rms_px_with_intrinsics_fixed"]
    dis = r["difference"]["model_disagreement_px"]["p95"]
    lines = []
    if r["views"] < 10:
        lines.append(f"Only {r['views']} usable views: shoot at least 15 for a trustworthy comparison.")
    if r["frame_coverage_4x4"] < 0.8:
        lines.append(f"Corners cover {r['frame_coverage_4x4']:.0%} of the frame: put the board in the corners/edges too, "
                     "otherwise distortion is not constrained there.")
    if rc > 1.0:
        lines.append(f"Your calibration RMS is {rc:.2f} px (>1 px): blurry shots, a bent board or bad detections. Fix that first.")
    if rp <= rc * 1.5 + 0.2 and dis < 2.0:
        lines.append(f"PHONE INTRINSICS LOOK GOOD: they explain your corners with {rp:.2f} px RMS vs {rc:.2f} px for a free "
                     f"calibration, and the two models agree within {dis:.2f} px over 95% of the frame.")
    else:
        lines.append(f"PHONE INTRINSICS DO NOT MATCH: {rp:.2f} px RMS with the phone's values vs {rc:.2f} px with your own, "
                     f"and the models differ by up to {dis:.1f} px (95th percentile). Use your calibration for this lens + focus.")
    return lines


# ---------------------------------------------------------------- command line

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("json", nargs="+", help="RawGeoCam JSON sidecars of checkerboard shots")
    ap.add_argument("--board", default="9x6", help="inner corners, columns x rows (default 9x6)")
    ap.add_argument("--square", type=float, default=0.025, help="square size in metres (default 0.025)")
    ap.add_argument("--fix-k3", action="store_true", help="hold k3 at 0 (use with few views on a narrow lens)")
    ap.add_argument("--out", default="verify_intrinsics_report.json")
    args = ap.parse_args(argv)

    import cv2
    from rawgeocam import Capture

    cols, rows = (int(x) for x in args.board.lower().split("x"))
    obj = board_points(cols, rows, args.square)

    caps = [Capture(p) for p in args.json]
    lens = {c.meta.get("summary", {}).get("lens") or c.meta["camera"]["id"] for c in caps}
    if len(lens) > 1:
        sys.exit(f"Captures come from different lenses: {sorted(map(str, lens))}. Use one lens per run.")
    focus = [c.meta["intrinsics"].get("focus_distance_diopters") for c in caps]
    focus = [f for f in focus if f is not None]
    if focus and max(focus) - min(focus) > 0.02:
        print(f"WARNING: focus varies between shots ({min(focus):.3f}..{max(focus):.3f} D). Intrinsics change with focus; "
              "use manual mode with one fixed focus value.")
    sources = {c.meta["intrinsics"]["source"] for c in caps}

    obj_list, img_list, used, size, K_phone, d_phone = [], [], [], None, None, None
    for c in caps:
        rgb, K, dist = c.image(frame="sensor", output_bps=8)
        gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
        s = (gray.shape[1], gray.shape[0])
        if size is None:
            size, K_phone = s, K
            raw_d = c.meta["intrinsics"]["distortion_opencv_k1_k2_p1_p2_k3"]
            d_phone = None if raw_d is None else np.asarray(raw_d, dtype=np.float64)
        elif s != size:
            print(f"skip {c.meta['image_file']}: size {s} != {size}")
            continue
        corners = detect_corners(gray, (cols, rows))
        if corners is None:
            print(f"skip {c.meta['image_file']}: board not found")
            continue
        obj_list.append(obj)
        img_list.append(corners)
        used.append(c.meta["image_file"])
        print(f"ok   {c.meta['image_file']}")

    if len(obj_list) < 3:
        sys.exit("Need at least 3 views with a detected board.")

    r = compare(K_phone, d_phone, obj_list, img_list, size, fix_k3=args.fix_k3)
    r["images"] = [used[i] for i in r["kept_view_indices"]]
    r["dropped_images"] = [used[i] for i in r["dropped_view_indices"]]
    for name in r["dropped_images"]:
        print(f"dropped {name}: reprojection error far above the other views (mis-detected or blurred board)")
    r["phone"]["intrinsics_source"] = sorted(sources)
    r["verdict"] = verdict(r)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(r, f, indent=2)

    np.set_printoptions(suppress=True, precision=3)
    print()
    print("Your calibration  K =\n", np.asarray(r["calibrated"]["K"]), "\n dist =", np.asarray(r["calibrated"]["dist_k1_k2_p1_p2_k3"]))
    print("Phone             K =\n", np.asarray(r["phone"]["K"]), "\n dist =", r["phone"]["dist_k1_k2_p1_p2_k3"])
    d = r["difference"]
    print(f"fx {d['fx_percent']:+.2f}%  fy {d['fy_percent']:+.2f}%  cx {d['cx_px']:+.1f} px  cy {d['cy_px']:+.1f} px")
    print()
    for line in r["verdict"]:
        print(line)
    print(f"\nReport: {args.out}")


if __name__ == "__main__":
    main()
