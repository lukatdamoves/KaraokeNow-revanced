package app.karaokenow.patch

import app.revanced.patcher.Fingerprint
import app.revanced.patcher.Match
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

/**
 * Fingerprint objects for the karaoke patch.
 *
 * API VERIFIED against the real CLI jar
 * (tools/revanced-cli-6.0.0-all.jar) with javap:
 *   fingerprint(int, FingerprintBuilder.() -> Unit): Fingerprint
 *   FingerprintBuilder: accessFlags(), returns(), parameters(),
 *     opcodes(Opcode...), opcodes(String), strings(),
 *     custom { method, classDef -> Boolean }
 *   FingerprintBuilder(int) — the int is `fuzzyPatternScanThreshold`
 *     (verified field name via javap -p). 0 is used below: the
 *     strictest setting, so the opcode patterns must match exactly.
 *     The precise threshold semantics are still a build-setup
 *     confirmation against revanced-patches-template.
 *   Fingerprint.matchOrNull(context) resolves a match;
 *     getMethodOrNull/getClassDefOrNull give the targets.
 *   bytecodePatch(name, description, use, builderAction)
 *   BytecodePatchBuilder: compatibleWith(Pair<String, Set<String>>...)
 *     AND compatibleWith(String...), extendWith(String |
 *     () -> InputStream), apply { ctx -> }
 *   NOTE on the "v6" label: the jar is ReVanced CLI 6.0.0. The
 *   label "patch API v6" could NOT be matched to a public API
 *   version (public numbering found: patcher v20, Manager API
 *   v4/v5, Morphe v4.x). Do NOT equate CLI/bundle 6.x numbering
 *   to a patcher API version. The DSL surface above is verified
 *   against the CLI 6.0.0 jar; confirm it against
 *   revanced-patches-template before finalizing patch code.
 *
 * STATUS LEGEND (applies to every object below):
 *   VERIFIED   — the target method/class was found in the stock
 *                YouTube 20.40.45 base.apk dex and its role was
 *                proven by opcode evidence (see
 *                patch/SPIKE_MUTE_SYNC.md for the evidence).
 *   BUILD GATE — the identification is VERIFIED, but the
 *                fingerprint's match itself is only proven when
 *                the patcher runs it at build time. A failed match
 *                stops the build loudly. It never falls back to
 *                another hook (standing rule).
 *
 * DEX EVIDENCE (VERIFIED, stock YouTube 20.40.45 base.apk,
 * re-downloaded 2026-10-26 after /tmp was wiped; sizes match the
 * spike exactly: .apkm 155,817,190 bytes, base.apk 54,226,263
 * bytes, classes2.dex 7,463,140 bytes).
 *
 * The main watch-player controller is the obfuscated class `Laiwx`
 * (classes2.dex, super java.lang.Object; ~60 fields; field `g`
 * holds the androidx/media3/exoplayer/ExoPlayer instance; field
 * `w` is a String). It is the ONLY ExoPlayer holder whose methods
 * consume com.google.android.libraries.youtube.innertube.model.media
 * .VideoStreamingData + PlayerConfigModel and return
 * FormatStreamModel — i.e. it is the class that plays a video.
 *
 * CORRECTION to the spike's §2.3 (verified against classes.dex):
 * the held ExoPlayer interface declares only 17 abstract methods,
 * but its superinterface `Lchl` declares the full player API
 * (A()Lchg; volume-params getter, K(Lchg;)V volume-params
 * setter, O(F)V float setter, w/x/y/z()J player longs, t()I
 * playback-state int, h(J)V / i(I,J)V seek-like, Q()/R()Z
 * booleans). So the spike's claim "no volume/position/seek
 * methods at all" was wrong in letter but right in practice:
 * the patch still goes through Laiwx's own wrapper methods, not
 * direct player calls — because the held object is reached only
 * through Laiwx, and Laiwx's wrappers carry the app's own state
 * bookkeeping (Lajcf updates, notifications). Do not write a
 * fingerprint that calls Player.setVolume/getCurrentPosition/
 * seekTo directly: no patch touches ExoPlayer directly (RVX
 * pattern, confirmed here by the wrapper structure).
 *
 * IDENTIFIED ROLES (each exactly one method, opcode evidence in
 * the spike report):
 *   - video ID:    Laiwx.i (Laiwl) -> Laiwl.l (Lajcf, current
 *                  state) -> Lajcf.j()String (= VideoStreaming-
 *                  Data.e = player-response videoId; see v5
 *                  note below). p()String is the 16-char
 *                  playback TOKEN, NOT the video ID — v5 fixes
 *                  this (v1-v4 shipped p() and every session
 *                  failed "This video is unavailable").
 *   - position:    e()J  (d()J is BUFFERED, not position)
 *   - volume:      as(F,Z)V  (L(F)V is its synchronized wrapper;
 *                  N(F)V is the normalizer, NOT the mute hook)
 *   - seek:        H(JLbcrd;)V  (j(J)J is a position-mapping
 *                  helper, NOT a seek; Lameo.an(JLbcrd;)Z is the
 *                  validated seek wrapper with the ad guard)
 *   - playing:     Q()Z  (T()Z is constant true, ELIMINATED;
 *                  R()Z is buffering; U()Z is stopped/ended)
 *   - bottom UI:   Lmjr.mi() inflates the bottom UI into
 *                  Lmju.r (see bottomUiInflate below)
 * ELIMINATED / NOT ROLES (do not match on these):
 *   - j(J)J: position-mapping helper (timescale/target mapping
 *     via Lcko.B + Laiym.pB), not a seek action.
 *   - N(F)V: volume normalization pipeline (1285+ code units,
 *     diagnostic strings, Laiml curves, Laium output). Calling
 *     N(0.0f) would feed 0 through the normalizer curves —
 *     unpredictable. Never use N for mute.
 *   - T()Z: body is `const/4 1; return` — constant true.
 *   - M(Z)V: play/pause control (ExoPlayer.ab(Z)V delegate,
 *     the playWhenReady setter — opcode-verified, see the
 *     playPause fingerprint). v13 role: the bridge pauses
 *     on session start and resumes when karaoke audio is
 *     ready — but M(false) proved ineffective as a pause, so
 *     patch_pause/patch_resume were deleted 2026-10-31 and
 *     the bridge now pauses via Laiwx.ar(Z,Z) instead.
 *     (v1 note said "not a v1 role" because the user
 *     paused via the UI then; v13 needs programmatic pause.)
 *   - AudioManager / setStreamMute: REJECTED (RVX's
 *     MuteVolumeButton uses STREAM_MUSIC setStreamMute, which is
 *     SYSTEM-WIDE and would mute the karaoke AudioTrack too).
 *     Our mute is player-internal: Laiwx.as -> ExoPlayer.K.
 *
 * DESIGN (RVX pattern, applied to Laiwx):
 * No patch code calls the player or Laiwx methods directly from
 * the extension. Instead the patch adds SYNTHETIC wrapper methods
 * on Laiwx that delegate to its own obfuscated methods
 * (patch_getSpeed()F -> a()F [PlaybackParameters.speed, NOT
 * volume — the old patch_getVolume name lied about this and
 * caused the v13 mute failure], patch_getPosition()J -> e()J,
 * patch_getVideoId()String -> Laiwx.i -> Laiwl.l ->
 * Lajcf.j()String, the player-response videoId; v5 replaces
 * the old p() delegation, which returned the playback token).
 * The four other v1 synthetics (patch_setVolume, patch_isPlaying,
 * patch_pause, patch_resume) were deleted 2026-10-31: zero call
 * sites, and two were traps (M(Z)V is not an effective pause;
 * L(F)V cannot take 0.0f — as() builds Lchg(0.0f), whose
 * constructor throws).
 * mechanism (MutableClassDef vs extension helper) is confirmed
 * against revanced-patches-template at build setup.
 * Button views are looked up at runtime BY RESOURCE NAME
 * (Resources.getIdentifier), NEVER by numeric resource ID.
 * The numeric IDs in the spike report are identification
 * evidence only, not patch code.
 */
object KaraokeFingerprints {

    /**
     * Laiwx class gate for the videoId synthetic (matches p()).
     * CORRECTION (v5, proven on phone logs + dex): p() does
     * NOT return the video ID. p() returns Laiwx.w, which is
     * the 16-character per-playback token (chain: w <-
     * Lajcf.a <- Laivf.h; every videoId in the user's logs
     * was 16 chars, real YouTube video IDs are 11 chars, and
     * the identical server player request with a real 11-char
     * ID returns OK while the 16-char token fails
     * "This video is unavailable"). The fingerprint is kept
     * ONLY to pin Laiwx for synthetic injection; the actual
     * videoId comes from the patch_getVideoId synthetic
     * (Laiwx.i -> Laiwl.l -> Lajcf.j()), NOT from p().
     * Opcode pattern below is p()'s consecutive run:
     * iget-object, iget-object, invoke-virtual, move-result-
     * object, iget-boolean.
     * BUILD GATE: patcher match at build time.
     */
    val videoId: Fingerprint = fingerprint(0) {
        returns("Ljava/lang/String;")
        // K2: p() takes no parameters (dex-verified) — pin that.
        parameters()
        opcodes(
            Opcode.IGET_OBJECT,
            Opcode.IGET_OBJECT,
            Opcode.INVOKE_VIRTUAL,
            Opcode.MOVE_RESULT_OBJECT,
            Opcode.IGET_BOOLEAN,
        )
        custom { _, classDef -> classDef.type == "Laiwx;" }
    }

    /**
     * Playback position source: Laiwx.e()J.
     * VERIFIED by opcode: az().c() + ExoPlayer.x()J, -1 if az()
     * is null. Caller-confirmed (complete 0-skip xref, 27
     * callers): H() reports e() after seeking; ay() = g() - e()
     * (remaining); progress runnables (Lamh, Lactp, Lagiq,
     * Laizc) poll it; Lajcf.w() feeds d()+e() together into the
     * Laiui.a progress snapshot. The d()/e() pair is the
     * buffered/position pair: d() uses player w(), e() uses
     * player x().
     * The custom block's invoke-target check ("x") is REQUIRED:
     * d() and e() share class, signature, flags, and opcode
     * shape — only the invoked player long (w vs x) differs.
     * Opcode pattern below is the consecutive run pcs 9-19:
     * invoke-virtual, move-result-wide, iget-object,
     * invoke-interface, move-result-wide, add-long/2addr
     * (byte 0xbb = ADD_LONG_2ADDR, confirmed by dexlib2's own
     * decode of this method).
     * BUILD GATE: patcher match at build time. The custom block
     * uses dexlib2 iface types (ReferenceInstruction,
     * MethodReference) — compile-checked at build setup.
     */
    val videoPosition: Fingerprint = fingerprint(0) {
        returns("J")
        // K2: e() takes no parameters (dex-verified) — pin that.
        parameters()
        opcodes(
            Opcode.INVOKE_VIRTUAL,
            Opcode.MOVE_RESULT_WIDE,
            Opcode.IGET_OBJECT,
            Opcode.INVOKE_INTERFACE,
            Opcode.MOVE_RESULT_WIDE,
            Opcode.ADD_LONG_2ADDR,
        )
        custom { method, classDef ->
            if (classDef.type != "Laiwx;") false
            else method.implementation?.instructions?.any { ins ->
                val ref = (ins as? ReferenceInstruction)?.reference
                ins.opcode == Opcode.INVOKE_INTERFACE &&
                    ref is MethodReference && ref.name == "x"
            } == true
        }
    }

    /**
     * Controller handoff: Laiwx.aB(...)Lajcb;.
     * VERIFIED by dex (dex.py --methods on classes2.dex, and
     * a dexlib2 probe of the same dex):
     * aB(String, VideoStreamingData, PlayerConfigModel, Larbh,
     * Laiuy, Integer, Z)Lajcb; acc 0x12 (PRIVATE|FINAL, NOT
     * static), so p0 is the controller instance. CORRECTION
     * 2026-10-31: 0x12 is PRIVATE|FINAL — PUBLIC|FINAL would
     * be 0x11 (a, e, p, Q, M, as are all 0x11). The old
     * comment said PUBLIC; the fingerprint now pins the true
     * flags, and the matcher requires exact equality, so a
     * PUBLIC pin here would have failed the build. The patch
     * injects KaraokeBridge.setController(p0) at its entry, so
     * the bridge always holds the current watch controller.
     * K2: full 7-param list + access flags + the entry opcode
     * run (pcs 0-7: iget-object, iget-object, invoke-virtual,
     * move-result) are all pinned below.
     * BUILD GATE: patcher match at build time.
     */
    val controllerEntry: Fingerprint = fingerprint(0) {
        returns("Lajcb;")
        parameters(
            "Ljava/lang/String;",
            "Lcom/google/android/libraries/youtube/innertube/model/media/VideoStreamingData;",
            "Lcom/google/android/libraries/youtube/innertube/model/media/PlayerConfigModel;",
            "Larbh;",
            "Laiuy;",
            "Ljava/lang/Integer;",
            "Z",
        )
        accessFlags(AccessFlags.PRIVATE, AccessFlags.FINAL)
        opcodes(
            Opcode.IGET_OBJECT,
            Opcode.IGET_OBJECT,
            Opcode.INVOKE_VIRTUAL,
            Opcode.MOVE_RESULT,
        )
        custom { method, classDef ->
            if (classDef.type != "Laiwx;") false
            else {
                val params = method.parameterTypes
                params.size >= 3
                    && params[0].toString() == "Ljava/lang/String;"
                    && params[1].toString() == "Lcom/google/android/libraries/youtube/innertube/model/media/VideoStreamingData;"
                    && params[2].toString() == "Lcom/google/android/libraries/youtube/innertube/model/media/PlayerConfigModel;"
            }
        }
    }

    /**
     * Bottom-UI inflate hook: Lmjr.mi()Ljava/lang/Object;.
     * VERIFIED by opcode (lmjr_mi.txt, classes dex): Lmjr is
     * the overlay host (field a:Lmju). mi() calls C(), then
     * inflates the bottom-UI stub (or the exploder variant)
     * into Lmju.r, then returns Lmju.o (controls layout).
     * mi() has exactly ONE return-object (pc 121), so the
     * exit-hook position is clean. At that return point only
     * v0 (the return value) and p0 (this) are live; v1 is dead,
     * so the injected hook uses v1 as scratch and never
     * clobbers the return value:
     *   iget-object v1, p0, Lmjr->a:Lmju;
     *   iget-object v1, v1, Lmju->r:View;
     *   invoke-static {v1}, Bridge->initializeBottomButtons(View)V
     * Lmju.r may be null (no bottom UI for this player type);
     * the bridge logs and skips, never crashes.
     * The opcode pattern below is mi()'s consecutive run
     * pcs 0-7 (iget-object, invoke-virtual C(), iget-object,
     * if-nez). The custom block's C-call check is REQUIRED:
     * it pins mi() as the method that drives C(), not just
     * any method with the same opcode shape.
     * HOOK DESIGN: at mi()'s exit the bridge resolves the
     * bottom bar BY RESOURCE NAME (bottom_end_container row
     * beside the fullscreen button, else the fullscreen
     * button's parent) and adds small "K" + "BV" text
     * buttons. Never a numeric ID in patch code. This
     * REPLACES the old Lmju.C() center-group hook: putting
     * a Button in player_control_button_wrapper landed a big
     * grey box over the video (phone screenshot 2026-10-26).
     * BUILD GATE: patcher match at build time.
     */
    val bottomUiInflate: Fingerprint = fingerprint(0) {
        returns("Ljava/lang/Object;")
        opcodes(
            Opcode.IGET_OBJECT,
            Opcode.INVOKE_VIRTUAL,
            Opcode.IGET_OBJECT,
            Opcode.IF_NEZ,
        )
        custom { method, classDef ->
            if (classDef.type != "Lmjr;") false
            else method.implementation?.instructions?.any { ins ->
                val ref = (ins as? ReferenceInstruction)?.reference
                ins.opcode == Opcode.INVOKE_VIRTUAL &&
                    ref is MethodReference && ref.name == "C" &&
                    ref.definingClass == "Lmju;"
            } == true
        }
    }
}

/**
 * Builds one synthetic delegating method on Laiwx. The method body
 * is built from BuilderInstructions directly (no smali parsing):
 * one invoke-virtual into Laiwx's own method, a move-result, and
 * a return. The extension calls these by reflection (see
 * KaraokeBridge): it never names an obfuscated method, so a
 * future YouTube rename only changes fingerprints, not the
 * extension. This is simpler than RVX's stub-body replacement
 * and verified against the CLI 6.0.0 patcher API.
 */
private fun synthetic(
    name: String,
    params: List<String>,
    returnType: String,
    registers: Int,
    instructions: List<BuilderInstruction>,
): MutableMethod {
    val immutable = ImmutableMethod(
        "Laiwx;",
        name,
        params.map { ImmutableMethodParameter(it, null, null) },
        returnType,
        AccessFlags.PUBLIC.value,
        emptySet(),
        emptySet(),
        null,
    )
    val mutable = MutableMethod(immutable)
    val impl = MutableMethodImplementation(registers)
    instructions.forEach { impl.addInstruction(it) }
    mutable.implementation = impl
    return mutable
}

private fun invokeVirtual(
    registerCount: Int,
    registers: List<Int>,
    targetName: String,
    targetParams: List<String>,
    targetReturn: String,
): BuilderInstruction35c {
    return invokeVirtualOn(
        registerCount, registers, "Laiwx;",
        targetName, targetParams, targetReturn,
    )
}

/**
 * invoke-virtual against a method on a class OTHER than Laiwx
 * (the plain invokeVirtual helper hardcodes Laiwx as the
 * defining class). The defining class MUST be the method's
 * real owner: a virtual call resolves name + signature
 * against the referenced class, so pointing j() at Laiwx
 * (which has no j()String) would throw NoSuchMethodError
 * at runtime even though the patcher accepts it.
 */
private fun invokeVirtualOn(
    registerCount: Int,
    registers: List<Int>,
    targetClass: String,
    targetName: String,
    targetParams: List<String>,
    targetReturn: String,
): BuilderInstruction35c {
    return BuilderInstruction35c(
        Opcode.INVOKE_VIRTUAL,
        registerCount,
        registers.getOrElse(0) { 0 },
        registers.getOrElse(1) { 0 },
        registers.getOrElse(2) { 0 },
        registers.getOrElse(3) { 0 },
        registers.getOrElse(4) { 0 },
        ImmutableMethodReference(
            targetClass, targetName, targetParams, targetReturn,
        ),
    )
}

/**
 * Fingerprint resolution + injection, run from the patch's execute
 * block. The patcher API is CONTEXTUAL (verified against the CLI
 * 6.0.0 jar + compiler probes): the execute block is a contextual
 * scope, so these helpers declare the same context and the
 * fingerprint calls take no context argument (fp.match(classDef),
 * not fp.match(ctx, classDef)). The no-arg matchOrNull() is
 * INTERNAL, so resolution goes through the public throwing
 * match(classDef) + Match.method / Match.classDef. Local helper
 * functions inside the execute block do NOT inherit its context,
 * which is why resolution lives in these top-level contextual
 * functions instead. Compile with -Xcontext-parameters.
 *
 * Every fingerprint resolves or the patch STOPS LOUDLY, naming
 * the fingerprint. It never silently continues and never tries
 * another hook (standing rule).
 */
context(ctx: BytecodePatchContext)
private fun classOrFail(type: String): ClassDef {
    return ctx.classes[type]
        ?: throw PatchException(
            "Karaoke: class $type not found in target dex.",
        )
}

context(ctx: BytecodePatchContext)
private fun matchOrFail(
    fp: Fingerprint,
    name: String,
    classDef: ClassDef,
): Match {
    return try {
        fp.match(classDef)
    } catch (e: Exception) {
        throw PatchException(
            "Karaoke: fingerprint '$name' did not match "
                + "YouTube 20.40.45 — patch stopped loudly "
                + "(no fallback).",
            e,
        )
    }
}

/**
 * K1: a fingerprint only proves a method matches a pattern —
 * it does not prove the synthetic calls THAT method. Assert
 * the matched method's name is the one the synthetic (or
 * hook) hardcodes; a mismatch stops the build loudly instead
 * of silently delegating to the wrong method. This is the
 * check that would have caught patch_getVolume -> a()
 * passing every gate while a() returns speed, not volume.
 */
private fun assertMethodName(
    match: Match,
    expected: String,
    fingerprintName: String,
) {
    val actual = match.method.name
    if (actual != expected) {
        throw PatchException(
            "Karaoke: fingerprint '$fingerprintName' matched "
                + "method '$actual', expected '$expected' — the "
                + "synthetic calls '$expected' by name, patch "
                + "stopped loudly.",
        )
    }
}

/**
 * K1: assert a field exists on a class (dexlib2 ClassDef
 * exposes fields/methods directly, so no patcher-specific
 * field API is needed). Used for the videoId field chain,
 * which no fingerprint verifies.
 */
private fun fieldOrFail(
    classDef: ClassDef,
    name: String,
    type: String,
    why: String,
) {
    if (classDef.fields.none { it.name == name && it.type == type }) {
        throw PatchException(
            "Karaoke: field ${classDef.type}->$name:$type "
                + "($why) not found in YouTube 20.40.45 — "
                + "patch stopped loudly.",
        )
    }
}

/** K1: assert a method exists on a class (see fieldOrFail). */
private fun methodOrFail(
    classDef: ClassDef,
    name: String,
    returnType: String,
    params: List<String>,
    why: String,
) {
    val found = classDef.methods.any {
        it.name == name && it.returnType == returnType
            && it.parameterTypes.map { p -> p.toString() } == params
    }
    if (!found) {
        throw PatchException(
            "Karaoke: method ${classDef.type}->$name("
                + params.joinToString(",") + ")$returnType "
                + "($why) not found in YouTube 20.40.45 — "
                + "patch stopped loudly.",
        )
    }
}

context(ctx: BytecodePatchContext)
private fun resolveAndInject() {
    val laiwxDef = classOrFail("Laiwx;")
    val lmjrDef = classOrFail("Lmjr;")
    val videoIdMatch =
        matchOrFail(KaraokeFingerprints.videoId, "videoId", laiwxDef)
    // K1: the synthetic must call the method the fingerprint
    // matched — a pattern match alone does not prove that.
    assertMethodName(videoIdMatch, "p", "videoId")
    val videoPositionMatch = matchOrFail(
        KaraokeFingerprints.videoPosition, "videoPosition", laiwxDef,
    )
    assertMethodName(videoPositionMatch, "e", "videoPosition")
    val bottomUiMatch = matchOrFail(
        KaraokeFingerprints.bottomUiInflate, "bottomUiInflate", lmjrDef,
    )
    assertMethodName(bottomUiMatch, "mi", "bottomUiInflate")
    val controllerMatch = matchOrFail(
        KaraokeFingerprints.controllerEntry, "controllerEntry", laiwxDef,
    )
    assertMethodName(controllerMatch, "aB", "controllerEntry")

    // K1: patch_getVideoId does NOT call p() — it walks the
    // field chain Laiwx.i -> Laiwl.l -> Lajcf.j(). No
    // fingerprint verifies that chain, so assert each link
    // exists here or the patch stops loudly.
    fieldOrFail(laiwxDef, "i", "Laiwl;", "videoId chain")
    val laiwlDef = classOrFail("Laiwl;")
    fieldOrFail(laiwlDef, "l", "Lajcf;", "videoId chain")
    val lajcfDef = classOrFail("Lajcf;")
    methodOrFail(
        lajcfDef, "j", "Ljava/lang/String;", emptyList(),
        "videoId chain",
    )

    val laiwx = videoIdMatch.classDef
    if (laiwx.methods.any { it.name.startsWith("patch_") }) {
        throw PatchException(
            "Karaoke: Laiwx already has patch_* methods — "
                + "refusing to add duplicates.",
        )
    }

    // Synthetic wrappers on Laiwx (see synthetic() above).
    // Register layouts: params are the LAST registers, so
    // p0 sits after the locals. v0 = result scratch.
    //   patch_getSpeed()F        regs=2 (v0=0, p0=1)
    //   patch_getPosition()J     regs=3 (v0=0, v1=1, p0=2)
    //   patch_getVideoId()String regs=2 (v0=0, p0=1)
    // Only three synthetics remain (K4): patch_setVolume,
    // patch_isPlaying, patch_pause, patch_resume were deleted
    // 2026-10-31 — zero call sites, two of them traps.
    // patch_getSpeed returns PlaybackParameters.speed (a()),
    // NOT volume (K6: the old patch_getVolume name lied).
    laiwx.methods.add(synthetic(
        "patch_getSpeed", emptyList(), "F", 2,
        listOf(
            invokeVirtual(1, listOf(1), "a", emptyList(), "F"),
            BuilderInstruction11x(Opcode.MOVE_RESULT, 0),
            BuilderInstruction11x(Opcode.RETURN, 0),
        ),
    ))
    laiwx.methods.add(synthetic(
        "patch_getPosition", emptyList(), "J", 3,
        listOf(
            invokeVirtual(1, listOf(2), "e", emptyList(), "J"),
            BuilderInstruction11x(Opcode.MOVE_RESULT_WIDE, 0),
            BuilderInstruction11x(Opcode.RETURN_WIDE, 0),
        ),
    ))
    //   patch_getVideoId()String regs=2 (v0=0, p0=1):
    //     iget Laiwx.i (Laiwl) -> iget Laiwl.l (Lajcf) ->
    //     Lajcf.j()String -> return. j() is VideoStreaming-
    //     Data.e, the player-response videoId (see v5 note).
    //     If Laiwl.l is null the invoke throws inside the
    //     synthetic; the bridge catches it and reports
    //     "could not read the video" — clean fail, no fallback.
    laiwx.methods.add(synthetic(
        "patch_getVideoId", emptyList(), "Ljava/lang/String;", 2,
        listOf(
            BuilderInstruction22c(
                Opcode.IGET_OBJECT, 0, 1,
                ImmutableFieldReference(
                    "Laiwx;", "i", "Laiwl;",
                ),
            ),
            BuilderInstruction22c(
                Opcode.IGET_OBJECT, 0, 0,
                ImmutableFieldReference(
                    "Laiwl;", "l", "Lajcf;",
                ),
            ),
            invokeVirtualOn(1, listOf(0), "Lajcf;", "j",
                emptyList(), "Ljava/lang/String;"),
            BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            BuilderInstruction11x(Opcode.RETURN_OBJECT, 0),
        ),
    ))

    // Controller handoff: at aB() entry, hand the controller
    // instance to the bridge. aB is PRIVATE|FINAL, NOT static
    // (dex-verified 2026-10-31; the old PUBLIC claim was
    // wrong — 0x12 is PRIVATE|FINAL), so p0 is `this`.
    val controllerMethod = controllerMatch.method
    controllerMethod.addInstructions(
        0,
        "invoke-static {p0}, "
            + "Lapp/karaokenow/extension/KaraokeBridge;"
            + "->setController(Ljava/lang/Object;)V",
    )

    // Bottom-button hook: at Lmjr.mi() exit (after the bottom
    // UI is inflated into Lmju.r), hand Lmju.r to the bridge,
    // which adds small "K" + "BV" buttons beside the fullscreen
    // button. mi() has exactly one RETURN_OBJECT (spike §2.5
    // + lmjr_mi.txt); any other count means the exit position
    // is ambiguous, so the patch stops instead of guessing.
    // Register safety at the return point: v0 holds the return
    // value (Lmju.o) and p0 is `this` — both live, never
    // clobbered. v1 is dead after the s-stub iput (pc 114),
    // so it is the scratch register. Both iget-objects are
    // format 22c (4-bit registers): v1 and p0 must both be
    // below 16, enforced loudly below (mi()'s p0 is v5).
    val bottomUiMethod = bottomUiMatch.method
    val bottomUiImpl = bottomUiMethod.implementation
        ?: throw PatchException(
            "Karaoke: Lmjr.mi() has no implementation.",
        )
    val bottomReturnIndices = bottomUiImpl.instructions
        .mapIndexedNotNull { index, ins ->
            if (ins.opcode == Opcode.RETURN_OBJECT) index else null
        }
    if (bottomReturnIndices.size != 1) {
        throw PatchException(
            "Karaoke: Lmjr.mi() has ${bottomReturnIndices.size} "
                + "RETURN_OBJECT instructions, expected exactly 1 — "
                + "exit-hook position ambiguous, patch stopped.",
        )
    }
    val miP0Reg = bottomUiImpl.registerCount - 1
    if (miP0Reg < 0 || miP0Reg > 15) {
        throw PatchException(
            "Karaoke: Lmjr.mi() registerCount invalid for 22c "
                + "(p0=$miP0Reg).",
        )
    }
    // K3 (verified, no guard needed): Lmjr.a cannot be null
    // at this exit hook. It is final (acc 0x1010), assigned
    // once by <init> at pc 0, and mi() itself invokes
    // Lmju.C() on it at pc 2 — a null would have thrown
    // inside mi() long before this return point. Lmju.r may
    // still be null; the bridge logs and skips that case.
    bottomUiMethod.addInstructions(
        bottomReturnIndices[0],
        listOf(
            BuilderInstruction22c(
                Opcode.IGET_OBJECT, 1, miP0Reg,
                ImmutableFieldReference(
                    "Lmjr;", "a", "Lmju;",
                ),
            ),
            BuilderInstruction22c(
                Opcode.IGET_OBJECT, 1, 1,
                ImmutableFieldReference(
                    "Lmju;", "r", "Landroid/view/View;",
                ),
            ),
            BuilderInstruction35c(
                Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0,
                ImmutableMethodReference(
                    "Lapp/karaokenow/extension/KaraokeBridge;",
                    "initializeBottomButtons",
                    listOf("Landroid/view/View;"),
                    "V",
                ),
            ),
        ),
    )
}

/**
 * The karaoke patch (v1 scope): button + mute hook + WS tunnel
 * client + parallel karaoke player + sync, single stream only.
 *
 * Wiring (this file): all four fingerprints resolve or the patch
 * stops loudly naming the fingerprint; three synthetic wrappers are
 * added to Laiwx (getSpeed/getPosition/getVideoId); setController is injected at
 * Laiwx.aB() entry; the bottom-button initializer is injected at
 * Lmjr.mi() exit. The extension (KaraokeBridge) calls the
 * synthetics by reflection and owns mute-first session start.
 * What the patcher match run + phone gate still have to prove is
 * recorded in patch/BUILD_REPORT.md.
 */
val karaokePatch = bytecodePatch(
    "Karaoke",
    "Adds a karaoke button: vocal-removed audio in sync with the "
            + "video, fetched through the phone over WebSocket "
            + "(v1, single instrumental stream).",
    true,
) {
    compatibleWith("com.google.android.youtube" to setOf("20.40.45"))

    // Extension dex built from patch/extension/ (javac + d8, see
    // patch/BUILD_REPORT.md). The name is the bundle entry path:
    // the .rvp zip carries it as "extensions/karaoke.rve".
    extendWith("extensions/karaoke.rve")

    execute { resolveAndInject() }
}
