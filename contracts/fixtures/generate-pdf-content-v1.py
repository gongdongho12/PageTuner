"""Independent framed SHA-256 vector; opaque storage bytes, not a PDF decoder test."""
import base64
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent
sha = lambda data: hashlib.sha256(data).hexdigest()


def digest(values):
    return sha(b"".join(str(len(value.encode("utf-8"))).encode("ascii") + b":" +
                        value.encode("utf-8") for value in values))


def nullable(value):
    return ["0"] if value is None else ["1", value]


pdf = (root / "portable-content-proof-v1/source.pdf").read_bytes()
png = (root / "portable-content-proof-v1/image.png").read_bytes()
paragraphs = [{"paragraphId": "p:😀", "text": "안녕 😀:|\n"}, {"paragraphId": "empty", "text": ""}]
pdf_path, image_path = "assets/" + sha(pdf), "assets/" + sha(png)
refs = [
    {"path": pdf_path, "role": "pdf", "paragraphId": None, "alt": None},
    {"path": image_path, "role": "image", "paragraphId": "p:😀", "alt": " 삽화 😀 "},
    {"path": image_path, "role": "image", "paragraphId": "empty", "alt": ""},
]
payloads = [{"path": path, "mimeType": mime, "base64": base64.b64encode(data).decode("ascii")}
            for path, mime, data in [(pdf_path, "application/pdf", pdf), (image_path, "image/png", png)]]
content = {"version": 1, "language": "ko-KR", "paragraphs": paragraphs, "assets": refs, "payloads": payloads}
paragraph_hash = digest(["pageturner.document-paragraphs.v1", str(len(paragraphs)),
                         *[value for p in paragraphs for value in [p["paragraphId"], p["text"]]]])
assets = []
for reference in refs:
    payload = next(p for p in payloads if p["path"] == reference["path"])
    data = base64.b64decode(payload["base64"])
    assets.append({**reference, "mimeType": payload["mimeType"], "byteLength": len(data), "sha256": sha(data)})
proof_frames = ["pageturner.content-proof.v1", "1", "PDF", content["language"], paragraph_hash,
                "1", str(len(pdf)), sha(pdf), str(len(assets))]
for asset in assets:
    proof_frames += [asset["path"], asset["role"], *nullable(asset["paragraphId"]), *nullable(asset["alt"]),
                     asset["mimeType"], str(asset["byteLength"]), asset["sha256"]]
proof = {"version": 1, "representation": "PDF", "language": content["language"], "paragraphHash": paragraph_hash,
         "originalFileByteLength": len(pdf), "originalFileSha256": sha(pdf), "assets": assets, "sha256": digest(proof_frames)}
request_frames = ["pageturner.pdf-content-upload.v1", "1", content["language"], str(len(paragraphs))]
for paragraph in paragraphs:
    request_frames += [paragraph["paragraphId"], paragraph["text"]]
request_frames += [str(len(refs))]
for reference in refs:
    request_frames += [reference["path"], reference["role"], *nullable(reference["paragraphId"]), *nullable(reference["alt"])]
request_frames += [str(len(payloads))]
for payload in payloads:
    request_frames += [payload["path"], payload["mimeType"], payload["base64"]]
vector = {"description": "Opaque PDF storage vector; does not prove PDF parsing or page count.",
          "upload": {"uploadId": "a2e5b2f6-0204-46c0-9f0a-0b50a8625f46", "content": content},
          "expected": {"requestFingerprint": digest(request_frames), "proof": proof}}
(root / "pdf-content-v1.json").write_text(json.dumps(vector, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"requestFingerprint": vector["expected"]["requestFingerprint"], "proof": proof["sha256"]}))
