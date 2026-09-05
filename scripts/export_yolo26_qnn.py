from pathlib import Path
from ultralytics import YOLO


def export_qnn(model_name: str, output_name: str) -> None:
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
    target = Path(output_name)
    if exported.resolve() != target.resolve():
        target.write_bytes(exported.read_bytes())
    if not target.exists() or target.stat().st_size == 0:
        raise RuntimeError(f"QNN export failed: {target}")
    print(f"exported {model_name} -> {target} ({target.stat().st_size} bytes)")


export_qnn("yolo26s.pt", "yolo26s_v79_qnn.onnx")
export_qnn("yolo26n.pt", "yolo26n_v79_qnn.onnx")
