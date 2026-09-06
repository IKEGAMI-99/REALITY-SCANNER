from pathlib import Path
import importlib
import shutil

import onnx
from ultralytics import YOLO


def patch_ultralytics_for_external_qnn() -> None:
    """Enable external QNN EPContext and allow its intentionally tiny wrapper ONNX file."""
    import ultralytics.utils.export.qnn as qnn_export
    import ultralytics.engine.exporter as exporter

    qnn_source = Path(qnn_export.__file__)
    qnn_text = qnn_source.read_text(encoding="utf-8")
    embedded = 'options.add_session_config_entry("ep.context_embed_mode", "1")'
    external = 'options.add_session_config_entry("ep.context_embed_mode", "0")'
    if external not in qnn_text:
        if embedded not in qnn_text:
            raise RuntimeError(f"Unable to locate QNN embed-mode setting in {qnn_source}")
        qnn_source.write_text(qnn_text.replace(embedded, external), encoding="utf-8")
        print(f"patched {qnn_source} to ep.context_embed_mode=0")

    exporter_source = Path(exporter.__file__)
    exporter_text = exporter_source.read_text(encoding="utf-8")
    old_check = 'assert mb > 0.1, f"{mb:.3f} MB output model too small (likely corrupt or unsupported ops)"'
    new_check = 'assert mb > 0.001, f"{mb:.3f} MB output model too small (likely corrupt or unsupported ops)"'
    if new_check not in exporter_text:
        if old_check not in exporter_text:
            raise RuntimeError(f"Unable to locate Ultralytics export-size check in {exporter_source}")
        exporter_source.write_text(exporter_text.replace(old_check, new_check), encoding="utf-8")
        print(f"patched {exporter_source} minimum output size for external QNN wrapper")

    importlib.invalidate_caches()
    qnn_export = importlib.reload(qnn_export)
    exporter = importlib.reload(exporter)

    if 'ep.context_embed_mode", "0"' not in Path(qnn_export.__file__).read_text(encoding="utf-8"):
        raise RuntimeError("QNN exporter reload verification failed")
    if "mb > 0.001" not in Path(exporter.__file__).read_text(encoding="utf-8"):
        raise RuntimeError("Ultralytics exporter size-check reload verification failed")
    print("reloaded Ultralytics exporter in external-QNN mode")


def normalize_epcontext_source(model_path: Path) -> None:
    model = onnx.load(str(model_path), load_external_data=False)
    changed = 0
    sources = []
    for node in model.graph.node:
        if node.op_type != "EPContext":
            continue
        for attr in node.attribute:
            if attr.name != "source":
                continue
            value = attr.s.decode("utf-8", errors="replace")
            sources.append(value)
            if value == "QNNExecutionProvider":
                attr.s = b"QnnExecutionProvider"
                changed += 1

    if changed:
        onnx.save(model, str(model_path))
        print(f"normalized {changed} EPContext source attribute(s) in {model_path}")
    else:
        print(f"EPContext source values in {model_path}: {sources}")

    verify = onnx.load(str(model_path), load_external_data=False)
    verify_sources = []
    for node in verify.graph.node:
        if node.op_type == "EPContext":
            for attr in node.attribute:
                if attr.name == "source":
                    verify_sources.append(attr.s.decode("utf-8", errors="replace"))
    if not verify_sources:
        raise RuntimeError(f"No EPContext source attribute found in {model_path}")
    invalid = [s for s in verify_sources if s not in {"QNN", "QnnExecutionProvider"}]
    if invalid:
        raise RuntimeError(f"Unsupported EPContext source values remain in {model_path}: {invalid}")
    print(f"verified EPContext source values: {verify_sources}")


def export_qnn(model_name: str, output_dir: str) -> None:
    target_dir = Path(output_dir)
    if target_dir.exists():
        shutil.rmtree(target_dir)
    target_dir.mkdir(parents=True, exist_ok=True)

    before_bins = {p.resolve() for p in Path(".").rglob("*.bin")}
    model = YOLO(model_name)
    exported = Path(
        model.export(
            format="qnn",
            name="79",
            imgsz=640,
            data="coco8.yaml",
            fraction=1.0,
            device="cpu",
        )
    )

    if not exported.exists() or exported.stat().st_size == 0:
        raise RuntimeError(f"QNN context ONNX was not generated for {model_name}: {exported}")

    context_onnx = target_dir / "model.onnx"
    shutil.copy2(exported, context_onnx)
    normalize_epcontext_source(context_onnx)

    after_bins = {p.resolve() for p in Path(".").rglob("*.bin")}
    new_bins = sorted(after_bins - before_bins)
    if not new_bins:
        raise RuntimeError(
            f"QNN export for {model_name} produced no external .bin file; external EPContext generation failed"
        )

    copied_total = 0
    for bin_path in new_bins:
        if bin_path.stat().st_size <= 0:
            continue
        target = target_dir / bin_path.name
        shutil.copy2(bin_path, target)
        copied_total += target.stat().st_size
        print(f"copied context binary {bin_path} -> {target}")

    if copied_total < 1024 * 1024:
        raise RuntimeError(
            f"External QNN context binaries for {model_name} total only {copied_total} bytes; expected >1 MB"
        )

    print(f"exported {model_name} -> {context_onnx} ({context_onnx.stat().st_size} bytes)")
    print(f"external QNN context total: {copied_total} bytes")


def export_plain_onnx(model_name: str, target_name: str, imgsz: int = 640) -> None:
    model = YOLO(model_name)
    exported = Path(
        model.export(
            format="onnx",
            imgsz=imgsz,
            simplify=True,
            dynamic=False,
            nms=True,
            device="cpu",
        )
    )
    target = Path(target_name)
    if exported.resolve() != target.resolve():
        shutil.copy2(exported, target)
    if not target.exists() or target.stat().st_size <= 1024 * 1024:
        raise RuntimeError(f"Plain ONNX export failed for {model_name}: {target}")
    print(f"exported plain fallback {model_name} imgsz={imgsz} -> {target} ({target.stat().st_size} bytes)")


patch_ultralytics_for_external_qnn()
export_qnn("yolo26s.pt", "qnn_s_bundle")
export_qnn("yolo26n.pt", "qnn_n_bundle")
export_plain_onnx("yolo26s.pt", "yolo26s.onnx", 640)
export_plain_onnx("yolo26n.pt", "yolo26n.onnx", 640)
# Speed-first model for ~20 FPS on the POCO F7 Ultra QNN-GPU path.
# 320px is ~1/4 the pixel compute of 640px while keeping the same YOLO26n weights.
export_plain_onnx("yolo26n.pt", "yolo26n_320.onnx", 320)
