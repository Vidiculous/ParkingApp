"""
Parking Detection System — FastAPI backend

Endpoints:
  POST /api/park        Update a person's parking status
  GET  /api/status      Return all current statuses + occupancy
  WS   /ws              Live push to all clients on every change
  GET  /                Serve the static dashboard
"""

import asyncio
import json
import logging
import os
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Annotated, Literal

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger(__name__)

# ── App ──────────────────────────────────────────────────────────────────────

app = FastAPI(title="Parking Detection System")

DASHBOARD_DIR = Path(__file__).parent.parent / "dashboard"
DASHBOARD_FILE = Path(__file__).parent / "dashboard.html"
STATE_FILE = Path(__file__).parent / "state.json"
TOTAL_SPOTS: int = int(os.environ.get("TOTAL_SPOTS", "7"))
STALE_AFTER_HOURS: int = int(os.environ.get("STALE_AFTER_HOURS", "24"))

# ── State ────────────────────────────────────────────────────────────────────
# {name: {dongle_id, status, since, manual}}

def _load_state() -> dict[str, dict]:
    try:
        if STATE_FILE.exists():
            return json.loads(STATE_FILE.read_text())
    except (OSError, json.JSONDecodeError) as exc:
        log.warning("Could not load state file, starting fresh: %s", exc)
    return {}

def _save_state(state: dict) -> None:
    try:
        STATE_FILE.write_text(json.dumps(state))
    except OSError as exc:
        log.error("Failed to persist state to %s: %s", STATE_FILE, exc)

parking_state: dict[str, dict] = _load_state()
_state_lock = asyncio.Lock()

# ── Occupancy ─────────────────────────────────────────────────────────────────

def _occupancy() -> dict:
    parked = sum(1 for v in parking_state.values() if v.get("status") == "parked")
    return {"parked": parked, "total": TOTAL_SPOTS, "full": parked >= TOTAL_SPOTS}

def _payload() -> dict:
    return {"users": parking_state, "occupancy": _occupancy()}

# ── WebSocket manager ────────────────────────────────────────────────────────

class ConnectionManager:
    def __init__(self):
        self.connections: list[WebSocket] = []

    async def connect(self, ws: WebSocket):
        await ws.accept()
        self.connections.append(ws)

    def disconnect(self, ws: WebSocket):
        if ws in self.connections:
            self.connections.remove(ws)

    async def broadcast(self, data: dict):
        message = json.dumps(data)
        dead = []
        for ws in self.connections:
            try:
                await ws.send_text(message)
            except Exception as exc:
                log.debug("WS send failed, marking dead: %s", exc)
                dead.append(ws)
        for ws in dead:
            self.disconnect(ws)


manager = ConnectionManager()

# ── Models ───────────────────────────────────────────────────────────────────

class ParkEvent(BaseModel):
    name: Annotated[str, Field(min_length=1, max_length=64)]
    dongle_id: Annotated[str, Field(min_length=1, max_length=64)]
    status: Literal["parked", "left"]
    manual: bool = False

# ── Routes ───────────────────────────────────────────────────────────────────

@app.post("/api/park")
async def park(event: ParkEvent):
    async with _state_lock:
        already_parked = parking_state.get(event.name, {}).get("status") == "parked"
        occ = _occupancy()
        warning = None
        if event.status == "parked" and occ["full"] and not already_parked:
            warning = "lot_full"

        parking_state[event.name] = {
            "dongle_id": event.dongle_id,
            "status": event.status,
            "since": datetime.now(timezone.utc).isoformat(),
            "manual": event.manual,
        }
        _save_state(parking_state)
        await manager.broadcast(_payload())
    return {"ok": True, **({"warning": warning} if warning else {})}


@app.get("/api/status")
async def status():
    return JSONResponse(_payload())


@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket):
    await manager.connect(ws)
    await ws.send_text(json.dumps(_payload()))
    try:
        while True:
            await asyncio.sleep(30)
            await ws.send_text('{"type":"ping"}')
    except WebSocketDisconnect:
        manager.disconnect(ws)
    except Exception as exc:
        log.warning("Unexpected WebSocket error, disconnecting: %s", exc)
        manager.disconnect(ws)


@app.get("/")
async def dashboard():
    for candidate in [DASHBOARD_DIR / "index.html", DASHBOARD_FILE]:
        if candidate.exists():
            return FileResponse(candidate)
    return JSONResponse({"error": "dashboard not found"}, status_code=404)

# ── Stale status cleanup ──────────────────────────────────────────────────────

async def _stale_cleanup():
    """Every hour, mark entries older than STALE_AFTER_HOURS as 'unknown'."""
    while True:
        await asyncio.sleep(3600)
        cutoff = datetime.now(timezone.utc) - timedelta(hours=STALE_AFTER_HOURS)
        changed = False
        async with _state_lock:
            for name, entry in parking_state.items():
                if entry.get("status") == "parked":
                    try:
                        since = datetime.fromisoformat(entry["since"])
                        if since < cutoff:
                            entry["status"] = "unknown"
                            changed = True
                    except (KeyError, ValueError) as exc:
                        log.warning("Bad 'since' value for %s in stale check: %s", name, exc)
            if changed:
                _save_state(parking_state)
                await manager.broadcast(_payload())


@app.on_event("startup")
async def startup():
    asyncio.create_task(_stale_cleanup())


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 8000))
    uvicorn.run("main:app", host="0.0.0.0", port=port, reload=False)
