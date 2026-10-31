"""Spike model: Demucs (htdemucs) window separator.

Why Demucs for the Phase 1 spike, when the plan's main model is
Mel-Band RoFormer: the spike's job is to prove TRANSPORT (chunked
streaming, timing, protocol) with a model whose API is stable and
well-known, before Phase 2 measures RoFormer on L4 and swaps it in
if it passes. Demucs is MIT-licensed, which also keeps the licensing
story clean. This module is deliberately small: load once, separate
one window, return stems. The RoFormer swap in Phase 2 only replaces
this module's internals; the pipeline and app do not change.

API contract (verified against the demucs package at deploy time if
the local torch test below cannot run here):
- Input: float32 PCM (channels, samples) at 44.1 kHz. Mono is
  duplicated to stereo (Demucs models expect stereo).
- Output: dict with 'instrumental' (drums+bass+other mixed) and
  'vocals' stems, same shape as the input window.
- The backing-vocal stream is NOT produced here: Demucs does not
  split lead vs backing. That is the Phase 2 stage-2 model. The app
  therefore streams 'instrumental' only until Phase 2 lands.

Imports of torch/demucs happen inside load(), so importing this
module never requires torch (pipeline tests stay torch-free).
"""

from __future__ import annotations

import numpy as np

from pipeline import SAMPLE_RATE

MODEL_NAME = "htdemucs"
STEMS = ("drums", "bass", "other", "vocals")
# htdemucs refuses windows longer than its training length
# (343,980 samples ~= 7.8 s at 44.1 kHz). Our 7 s windows
# (308,700 samples) fit with margin; fail loudly here instead of
# deep inside Demucs if the recipe ever grows past the limit again.
HTDEMUCS_MAX_SAMPLES = 343980


class DemucsSeparator:
    def __init__(self) -> None:
        self._model = None
        self._device = None
        self._torch = None

    def load(self) -> None:
        import torch
        from demucs.apply import apply_model
        from demucs.pretrained import get_model

        self._torch = torch
        self._apply_model = apply_model
        # M3: GPU-only by design ("never cpu") — fail loudly here
        # instead of silently separating on CPU for ~10x the time
        # while /health still reports model_loaded: true.
        if not torch.cuda.is_available():
            raise RuntimeError(
                "DemucsSeparator requires a CUDA GPU; refusing CPU fallback"
            )
        self._device = "cuda"
        model = get_model(MODEL_NAME)
        model.to(self._device)
        model.eval()
        self._model = model

    @property
    def device(self) -> str | None:
        """'cuda' once loaded, None before load()."""
        return self._device

    def separate_window(self, pcm: np.ndarray) -> dict:
        """Separate one window. Returns {'instrumental', 'vocals'}."""
        if self._model is None:
            raise RuntimeError("DemucsSeparator.load() must run first")
        torch = self._torch
        wav = pcm.astype(np.float32)
        if wav.shape[0] == 1:
            wav = np.concatenate([wav, wav], axis=0)
        if wav.shape[1] > HTDEMUCS_MAX_SAMPLES:
            raise ValueError(
                f"window {wav.shape[1]} samples exceeds htdemucs limit "
                f"{HTDEMUCS_MAX_SAMPLES}; shorten WINDOW_SECONDS"
            )
        tensor = torch.from_numpy(wav).unsqueeze(0).to(self._device)
        with torch.no_grad():
            # split=False: our windowing already provides context and
            # edge handling; do not let apply_model re-split the window.
            out = self._apply_model(
                self._model, tensor, shifts=0, split=False, overlap=0
            )
        # out: (1, 4 stems, channels, samples); stems in STEMS order.
        stems = out[0].detach().cpu().numpy()
        instrumental = stems[0] + stems[1] + stems[2]  # drums+bass+other
        vocals = stems[3]
        return {"instrumental": instrumental, "vocals": vocals}
