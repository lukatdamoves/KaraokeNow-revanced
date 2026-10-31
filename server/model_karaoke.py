"""Stage-2 lead-vocal separator for the backing-vocal stream.

The karaoke BS-RoFormer (becruily/Frazer; anvuew before v56)
is a lead-vocal extractor: run on Demucs' vocal stem it predicts
the lead vocal, and `vocals - lead` is the backing-vocal stem
(harmonies, doubles, answers, vocal reverb tails). This matches
the Phase-2 design in PLAN.md (stage 2 runs on the vocal stem,
not the full mix). Swapped anvuew -> becruily because lead
vocals audibly leaked into the backing stream (same 204MB arch,
same speed, verified stem names/sample rate/inference params).

Inference uses the maintained bs-roformer-infer package's own
model construction + overlap-add demix helpers and its
SHA-256-verified checkpoint registry, so weights are never
fetched from an unverified mirror. Imports are lazy: importing
this module needs only numpy, so local protocol tests stay
torch-free (same pattern as model_demucs.py).
"""

from __future__ import annotations

import numpy as np

MODEL_NAME = "bs-roformer-karaoke-becruily"
MODEL_SLUG = "roformer-model-bs-roformer-karaoke-by-becruily"


class KaraokeLeadSeparator:
    """Loads the karaoke BS-RoFormer once; separates per window."""

    def __init__(self) -> None:
        self._model = None
        self._config = None
        self._device = None
        self._torch = None
        self._demix_track = None

    def load(self) -> None:
        import torch
        import yaml
        from bs_roformer import ensure_model_assets, get_model_from_config
        from bs_roformer.inference import SafeLoaderWithTuple
        from bs_roformer.utils import demix_track, load_checkpoint_state
        from ml_collections import ConfigDict

        checkpoint_path, config_path = ensure_model_assets(MODEL_SLUG)
        # M3: GPU-only by design — fail loudly BEFORE loading the
        # 204MB checkpoint instead of silently running on CPU.
        if not torch.cuda.is_available():
            raise RuntimeError(
                "KaraokeLeadSeparator requires a CUDA GPU; "
                "refusing CPU fallback"
            )
        with open(config_path) as handle:
            self._config = ConfigDict(
                yaml.load(handle, Loader=SafeLoaderWithTuple)
            )
        # v59: single-chunk demix. num_overlap=4 ran ~2 full-model
        # forwards per 7s window (step 220500 < window 308700);
        # overlap=1 runs ONE (window < chunk 882000, zero internal
        # boundaries) at identical quality for our sub-chunk
        # windows — and our centre-cut discards edges anyway.
        # Halves the ~3.7s stage-2 cost (Modal-measured).
        self._config.inference.num_overlap = 1
        model = get_model_from_config("bs_roformer", self._config)
        model.load_state_dict(
            load_checkpoint_state(checkpoint_path, map_location="cpu")
        )
        self._device = "cuda"
        model.to(self._device)
        model.eval()
        self._model = model
        self._torch = torch
        self._demix_track = demix_track

    @property
    def device(self) -> str | None:
        """'cuda' once loaded, None before load()."""
        return self._device

    @property
    def loaded(self) -> bool:
        return self._model is not None

    def separate_lead(self, vocals: np.ndarray) -> np.ndarray:
        """Predict the lead vocal inside a Demucs vocal window.

        Input/output: float32 PCM (channels, samples), 44.1 kHz.
        The output is cropped/padded to the input length so the
        caller's subtraction is always sample-aligned.
        """
        if self._model is None:
            raise RuntimeError("KaraokeLeadSeparator.load() must run first")
        wav = vocals.astype(np.float32)
        if wav.shape[0] == 1:
            wav = np.concatenate([wav, wav], axis=0)
        tensor = self._torch.from_numpy(wav)
        # v59: first_chunk_time=0.0 silences demix_track's per-call
        # stdout time estimates (40+ lines of Modal log noise).
        sources, _first_chunk_time = self._demix_track(
            self._config, self._model, tensor, self._device, 0.0
        )
        lead = np.asarray(sources["Vocals"], dtype=np.float32)
        if lead.shape[0] == 1 and wav.shape[0] == 2:
            lead = np.concatenate([lead, lead], axis=0)
        if lead.shape[1] > wav.shape[1]:
            lead = lead[:, : wav.shape[1]]
        elif lead.shape[1] < wav.shape[1]:
            pad = np.zeros(
                (lead.shape[0], wav.shape[1] - lead.shape[1]),
                dtype=np.float32,
            )
            lead = np.concatenate([lead, pad], axis=1)
        return lead

    def separate_backing(self, vocals: np.ndarray) -> np.ndarray:
        """Backing vocals = Demucs vocals minus predicted lead."""
        lead = self.separate_lead(vocals)
        return (vocals.astype(np.float32) - lead).astype(np.float32)
