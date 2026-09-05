from pathlib import Path
import shutil

from ultralytics import YOLO


def force_external_context_mode() -> None:
    """Work around ORT QNN embedded EPContext loading issues by keeping the context binary external."""
    import ultralytics.utils.export.qnn as qnn_export

    source = Path(qnn_export.__file__)
    text = source.read_text(encoding="utf-8")
    embedded = 'options.add_session_config_entry("ep.context_embed_mode", "1")'
    external = 'options.add_session_config_entry("ep.context_embed_mode", "0")'
    if external in text:
        return
    if embedded not in text:
        raise RuntimeError(f"Unable to locate QNN embed-mode setting in {source}")
    source.write_text(text.replace(embedded, external), encoding="utf-8")
    print(f"patched {source} to ep.context_embed_mode=0")


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

    after_bins = {p.resolve() for p in Path(".").rglob("*.bin")}
    new_bins = sorted(after_bins - before_bins)
    if not new_bins:
        raise RuntimeError(
            f"QNN export for {model_name} produced no external .bin file; "
            "embedded context mode may still be active"
        )

    for bin_path in new_bins:
        target = target_dir / bin_path.name
        shutil.copy2(bin_path, target)
        print(f"copied context binary {bin_path} -> {target}")

    print(f"exported {model_name} -> {context_onnx} ({context_onnx.stat().st_size} bytes)")
    print("bundle contents:")
    for path in sorted(target_dir.iterdir()):
        print(f"  {path.name}: {path.stat().st_size} bytes")


force_external_context_mode()
export_qnn("yolo26s.pt", "qnn_s_bundle")
export_qnn("yolo26n.pt", "qnn_n_bundle")
