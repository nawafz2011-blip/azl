"""
عزل: فصل الصوت عن الموسيقى. يشتغل مجانًا على جهازك.

التشغيل:
    pip install -r requirements.txt
    uvicorn app:app
ثم افتح http://localhost:8000

Modes:
  speech : remove music from a clip, keep the human voice (video is re-muxed with the clean voice)
  song   : split a song into vocals and instrumental

Input: uploaded audio/video file, or a link (YouTube and others via yt-dlp).
Engine: Demucs (Hybrid Transformer) running on CPU or GPU.
"""
import os
import shutil
import subprocess
import threading
import time
import types
import uuid
import queue
import json
import re
from pathlib import Path

import numpy as np
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

BASE = Path(__file__).parent
WORK = Path(os.environ.get("WORK_DIR", "/tmp/soundsplit-jobs"))
WORK.mkdir(parents=True, exist_ok=True)

MODEL_NAME = os.environ.get("MODEL", "htdemucs")          # htdemucs_ft = better quality, ~4x slower
MAX_MINUTES = float(os.environ.get("MAX_MINUTES", "20"))  # longest input accepted
MAX_UPLOAD_MB = int(os.environ.get("MAX_UPLOAD_MB", "500"))
JOB_TTL_MIN = int(os.environ.get("JOB_TTL_MIN", "60"))   # results are deleted after this
FAKE = os.environ.get("FAKE_SEPARATOR") == "1"            # testing only: no model, ffmpeg filters
SR = 44100


def find_ffmpeg() -> str:
    """ffmpeg من الجهاز، وإذا مو موجود ناخذ النسخة الجاهزة من imageio-ffmpeg."""
    path = shutil.which("ffmpeg")
    if path:
        return path
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


FFMPEG = find_ffmpeg()


class RateLimiter:
    def __init__(self, limit: int, per_sec: int):
        self.limit, self.per = limit, per_sec
        self.hits: dict[str, list] = {}
        self.lock = threading.Lock()

    def check(self, key: str):
        now = time.time()
        with self.lock:
            q = [t for t in self.hits.get(key, []) if t > now - self.per]
            if len(q) >= self.limit:
                raise HTTPException(429, "طلبات كثيرة، انتظر شوي وجرّب مرة ثانية")
            q.append(now)
            self.hits[key] = q


job_limit = RateLimiter(int(os.environ.get("JOBS_PER_HOUR", "20")), 3600)

app = FastAPI(title="عزل")
jobs: dict[str, dict] = {}
jobs_lock = threading.Lock()
work_q: "queue.Queue[str]" = queue.Queue()


class JobError(Exception):
    """Error with a message meant for the user (Arabic)."""


# ---------------------------------------------------------------- helpers
def run(cmd, timeout=None):
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if p.returncode != 0:
        raise RuntimeError(f"{cmd[0]} failed: {p.stderr[-1500:]}")
    return p.stdout


def probe(path: Path) -> dict:
    """معلومات الملف من ffmpeg -i (ما نحتاج ffprobe)."""
    err = subprocess.run([FFMPEG, "-hide_banner", "-i", str(path)],
                         capture_output=True, text=True, errors="replace").stderr
    m = re.search(r"Duration: (\d+):(\d+):([\d.]+)", err)
    duration = int(m[1]) * 3600 + int(m[2]) * 60 + float(m[3]) if m else 0.0
    has_audio = re.search(r"Stream #.*: Audio:", err) is not None
    video = None
    for line in err.splitlines():
        v = re.search(r"Stream #.*: Video: (\w+)", line)
        if v and "attached pic" not in line:   # صورة الغلاف في mp3 مو فيديو
            video = {"codec_name": v[1]}
            break
    return {"has_audio": has_audio, "video": video, "duration": duration}


def update(job_id, **kw):
    with jobs_lock:
        jobs[job_id].update(kw)


def load_audio(path: Path) -> np.ndarray:
    """Decode any media file to float32 stereo [2, n] at 44.1 kHz via ffmpeg."""
    raw = subprocess.run(
        [FFMPEG, "-v", "error", "-i", str(path), "-vn", "-f", "f32le",
         "-ac", "2", "-ar", str(SR), "-"],
        capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).reshape(-1, 2).T.copy()


def save_audio(wav: np.ndarray, out: Path):
    """Write [2, n] float32 to mp3 via ffmpeg."""
    wav = np.clip(wav, -1, 1).T.astype(np.float32).tobytes()
    p = subprocess.run(
        [FFMPEG, "-v", "error", "-y", "-f", "f32le", "-ac", "2", "-ar", str(SR), "-i", "-",
         "-codec:a", "libmp3lame", "-b:a", "192k", str(out)],
        input=wav, capture_output=True)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode()[-1000:])


# ---------------------------------------------------------------- model
_model = None
_model_lock = threading.Lock()


def get_model():
    global _model
    with _model_lock:
        if _model is None:
            from demucs.pretrained import get_model as gm
            _model = gm(MODEL_NAME)
            _model.eval()
        return _model


def separate(wav: np.ndarray, progress) -> tuple[np.ndarray, np.ndarray]:
    """Return (vocals, accompaniment), each [2, n]."""
    if FAKE:
        progress(1.0)
        return wav * 0.5, wav * 0.5

    import torch
    import demucs.apply as dapply

    model = get_model()
    device = "cuda" if torch.cuda.is_available() else "cpu"
    mix = torch.from_numpy(wav)
    ref = mix.mean(0)
    mean, std = ref.mean(), ref.std() + 1e-8
    x = ((mix - mean) / std)[None]

    # Hook demucs' tqdm so we can report real progress.
    class Tracker:
        def __init__(self, it, **kw):
            self.items = list(it)

        def __iter__(self):
            n = len(self.items)
            for i, item in enumerate(self.items):
                yield item
                progress((i + 1) / n)

    orig = dapply.tqdm
    dapply.tqdm = types.SimpleNamespace(tqdm=Tracker)
    try:
        with torch.no_grad():
            out = dapply.apply_model(model, x, device=device, shifts=1, split=True,
                                     overlap=0.25, progress=True, num_workers=0)[0]
    finally:
        dapply.tqdm = orig

    out = out * std + mean
    names = model.sources
    vi = names.index("vocals")
    vocals = out[vi]
    accomp = sum(out[i] for i in range(len(names)) if i != vi)
    return vocals.cpu().numpy(), accomp.cpu().numpy()


# ---------------------------------------------------------------- pipeline
def download_url(job_id: str, url: str, dest: Path) -> Path:
    import yt_dlp

    def hook(d):
        if d.get("status") == "downloading":
            tot = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            if tot:
                update(job_id, progress=0.15 * d.get("downloaded_bytes", 0) / tot)

    def length_filter(info, *, incomplete):
        dur = info.get("duration")
        if dur and dur > MAX_MINUTES * 60:
            return f"too long ({dur}s)"
        return None

    want_video = jobs[job_id]["mode"] == "speech"
    opts = {
        "outtmpl": str(dest / "source.%(ext)s"),
        "format": "bv*+ba/b" if want_video else "bestaudio/best",
        "merge_output_format": "mp4",
        "noplaylist": True,
        "noprogress": True,
        # حماية: نمنع المستخرج العام عشان ما أحد يخلي السيرفر يفتح روابط داخلية
        "allowed_extractors": ["default", "-generic"],
        "ffmpeg_location": FFMPEG,
        "format_sort": ["res:720", "vcodec:h264", "acodec:aac"],
        "quiet": True,
        "no_warnings": True,
        "progress_hooks": [hook],
        "match_filter": length_filter,
        "max_filesize": MAX_UPLOAD_MB * 1024 * 1024,
    }
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
    except Exception as e:
        raise JobError("تعذّر تحميل الرابط. تأكد أنه رابط صحيح لمقطع عام.") from e
    if info is None:
        raise JobError(f"المقطع أطول من الحد المسموح ({int(MAX_MINUTES)} دقيقة).")
    update(job_id, title=info.get("title") or "")
    files = [f for f in dest.glob("source.*") if not f.name.endswith(".part")]
    if not files:
        raise JobError(f"المقطع أطول من الحد المسموح ({int(MAX_MINUTES)} دقيقة) أو تعذّر تحميله.")
    return files[0]


def process(job_id: str):
    job = jobs[job_id]
    d = WORK / job_id
    try:
        update(job_id, status="running", stage="download" if job.get("url") else "prepare")
        src = download_url(job_id, job["url"], d) if job.get("url") else Path(job["src"])

        update(job_id, stage="prepare", progress=0.15)
        info = probe(src)
        if not info["has_audio"]:
            raise JobError("الملف ما فيه صوت.")
        if info["duration"] > MAX_MINUTES * 60:
            raise JobError(f"المقطع أطول من الحد المسموح ({int(MAX_MINUTES)} دقيقة).")
        wav = load_audio(src)

        update(job_id, stage="model", progress=0.18)
        get_model() if not FAKE else None

        update(job_id, stage="separate", progress=0.2)
        vocals, music = separate(wav, lambda p: update(job_id, progress=0.2 + 0.7 * p))

        update(job_id, stage="export", progress=0.92)
        outputs = []
        save_audio(vocals, d / "voice.mp3")
        outputs.append({"name": "voice.mp3", "kind": "audio", "label": "الصوت البشري فقط"})
        save_audio(music, d / "music.mp3")
        outputs.append({"name": "music.mp3", "kind": "audio", "label": "الموسيقى فقط"})

        if job["mode"] == "speech" and info["video"]:
            out_v = d / "video-no-music.mp4"
            base = [FFMPEG, "-v", "error", "-y", "-i", str(src), "-i", str(d / "voice.mp3"),
                    "-map", "0:v:0", "-map", "1:a:0", "-shortest", "-movflags", "+faststart"]
            codec = info["video"].get("codec_name")
            try:
                if codec != "h264":
                    raise RuntimeError("re-encode")
                run(base + ["-c:v", "copy", "-c:a", "aac", "-b:a", "160k", str(out_v)])
            except RuntimeError:
                run(base + ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                            "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k", str(out_v)])
            outputs.insert(0, {"name": out_v.name, "kind": "video", "label": "الفيديو بدون موسيقى"})

        # we keep only results; drop the source to save disk
        if src.exists() and src.parent == d:
            src.unlink()
        update(job_id, status="done", stage="done", progress=1.0, outputs=outputs,
               duration=info["duration"])
    except JobError as e:
        update(job_id, status="error", error=str(e))
    except Exception as e:  # noqa: BLE001
        print(f"[job {job_id}] failed: {e!r}", flush=True)
        update(job_id, status="error", error="صار خطأ أثناء المعالجة. جرّب ملف ثاني أو أعد المحاولة.")


def worker():
    while True:
        job_id = work_q.get()
        try:
            process(job_id)
        finally:
            work_q.task_done()


def janitor():
    while True:
        time.sleep(300)
        cutoff = time.time() - JOB_TTL_MIN * 60
        with jobs_lock:
            old = [k for k, v in jobs.items() if v["created"] < cutoff and v["status"] in ("done", "error")]
            for k in old:
                jobs.pop(k, None)
        for k in old:
            shutil.rmtree(WORK / k, ignore_errors=True)


threading.Thread(target=worker, daemon=True).start()
threading.Thread(target=janitor, daemon=True).start()


# ---------------------------------------------------------------- API
def public(job: dict, job_id: str) -> dict:
    with jobs_lock:
        ahead = 0
        if job["status"] == "queued":
            ahead = sum(1 for v in jobs.values() if v["status"] == "running") + sum(
                1 for v in jobs.values() if v["status"] == "queued" and v["created"] < job["created"])
    keys = ("status", "stage", "progress", "error", "outputs", "mode", "title", "duration")
    return {"id": job_id, "ahead": ahead, **{k: job.get(k) for k in keys}}


@app.post("/api/jobs")
async def create_job(request: Request, mode: str = Form(...), url: str = Form(""),
                     file: UploadFile | None = File(None)):
    job_limit.check(request.client.host if request.client else "?")
    if mode not in ("speech", "song"):
        raise HTTPException(400, "وضع غير معروف")
    url = url.strip()
    if not file and not url:
        raise HTTPException(400, "ارفع ملف أو حط رابط")
    if url and not url.startswith(("http://", "https://")):
        raise HTTPException(400, "الرابط لازم يبدأ بـ https://")

    job_id = uuid.uuid4().hex[:12]
    d = WORK / job_id
    d.mkdir(parents=True)
    job = {"status": "queued", "stage": "queued", "progress": 0.0, "mode": mode,
           "created": time.time(), "outputs": [], "title": ""}

    if file and file.filename:
        ext = Path(file.filename).suffix.lower()[:8] or ".bin"
        dest = d / f"source{ext}"
        size, limit = 0, MAX_UPLOAD_MB * 1024 * 1024
        with dest.open("wb") as f:
            while chunk := await file.read(1 << 20):
                size += len(chunk)
                if size > limit:
                    f.close()
                    shutil.rmtree(d, ignore_errors=True)
                    raise HTTPException(413, f"الملف أكبر من {MAX_UPLOAD_MB} ميجا")
                f.write(chunk)
        job["src"] = str(dest)
        job["title"] = Path(file.filename).stem
    else:
        job["url"] = url

    with jobs_lock:
        jobs[job_id] = job
    work_q.put(job_id)
    return public(job, job_id)


@app.get("/api/jobs/{job_id}")
def get_job(job_id: str):
    job = jobs.get(job_id)
    if not job:
        raise HTTPException(404, "الطلب غير موجود أو انتهت صلاحيته")
    return public(job, job_id)


@app.get("/api/jobs/{job_id}/files/{name}")
def get_file(job_id: str, name: str, download: int = 0):
    job = jobs.get(job_id)
    if not job or name not in {o["name"] for o in job.get("outputs", [])}:
        raise HTTPException(404)
    path = WORK / job_id / name
    title = (job.get("title") or "azil").strip()[:60]
    fname = f"{title} - {name}" if download else None
    return FileResponse(path, filename=fname)


@app.get("/api/config")
def config():
    return {"max_minutes": MAX_MINUTES, "max_upload_mb": MAX_UPLOAD_MB, "model": MODEL_NAME}


@app.get("/healthz")
def health():
    return JSONResponse({"ok": True})


app.mount("/", StaticFiles(directory=BASE / "static", html=True), name="static")
