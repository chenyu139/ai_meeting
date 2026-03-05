/* eslint-disable no-console */
(() => {
  const $ = (id) => document.getElementById(id);
  const state = {
    meetingId: "",
    taskId: "",
    joinUrl: "",
    ws: null,
    stream: null,
    mediaRecorder: null,
    audioContext: null,
    sourceNode: null,
    processorNode: null,
    sendTimer: null,
    pcmQueue: [],
    isStreaming: false,
    isPaused: false,
    chunkIndex: 0,
    shareToken: "",
    shareUrl: "",
    syntheticSendTimer: null,
    syntheticUploadTimer: null,
    syntheticPhase: 0
  };

  const logView = $("logView");
  const transcriptView = $("transcriptView");
  const summaryView = $("summaryView");
  const shareView = $("shareView");

  function now() {
    return new Date().toLocaleTimeString();
  }

  function log(level, message, data) {
    const prefix = `[${now()}][${level}] ${message}`;
    const body = data === undefined ? "" : `\n${safeStringify(data)}`;
    logView.textContent = `${prefix}${body}\n${logView.textContent}`.slice(0, 120000);
  }

  function safeStringify(obj) {
    try {
      return JSON.stringify(obj, null, 2);
    } catch (_e) {
      return String(obj);
    }
  }

  function setTag(id, text) {
    const el = $(id);
    el.innerHTML = `<span class="tag">${text}</span>`;
  }

  function setText(id, text) {
    $(id).textContent = text || "-";
  }

  function apiBase() {
    const base = $("baseUrl").value.trim() || window.location.origin;
    const prefix = $("apiPrefix").value.trim() || "/api/v1";
    return { base: base.replace(/\/+$/, ""), prefix: prefix.startsWith("/") ? prefix : `/${prefix}` };
  }

  function headers(extra) {
    const userId = $("userId").value.trim();
    const h = extra ? { ...extra } : {};
    if (userId) {
      h["X-User-Id"] = userId;
    }
    return h;
  }

  async function request(method, path, opts) {
    const options = opts || {};
    const cfg = apiBase();
    const url = `${cfg.base}${cfg.prefix}${path}`;
    const init = { method, headers: headers(options.headers) };
    if (options.formData) {
      init.body = options.formData;
      delete init.headers["Content-Type"];
    } else if (options.body !== undefined) {
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(options.body);
    }
    const resp = await fetch(url, init);
    let data;
    const text = await resp.text();
    try {
      data = text ? JSON.parse(text) : {};
    } catch (_e) {
      data = text;
    }
    log("HTTP", `${method} ${path} -> ${resp.status}`, data);
    if (!resp.ok) {
      const detail = typeof data === "object" ? (data.detail || data.message || safeStringify(data)) : String(data);
      throw new Error(`HTTP ${resp.status}: ${detail}`);
    }
    return data;
  }

  function randomIdempotencyKey(prefix) {
    return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
  }

  function randomEventId() {
    return crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
  }

  function updateMeetingMeta(resp) {
    state.meetingId = resp.meeting_id || resp.meetingId || state.meetingId;
    state.taskId = resp.task_id || resp.taskId || state.taskId;
    state.joinUrl = resp.meeting_join_url || resp.meetingJoinUrl || state.joinUrl;
    $("joinUrl").value = state.joinUrl || "";
    setText("vMeetingId", state.meetingId);
    setText("vTaskId", state.taskId);
    if (resp.status) {
      setTag("vMeetingStatus", resp.status);
    }
  }

  async function healthCheck() {
    const cfg = apiBase();
    const resp = await fetch(`${cfg.base}/healthz`);
    const data = await resp.json();
    log("OK", "健康检查完成", data);
  }

  async function createMeeting() {
    const title = $("meetingTitle").value.trim() || "Web模拟会议";
    const data = await request("POST", "/meetings", {
      headers: { "Idempotency-Key": randomIdempotencyKey("web-create") },
      body: { title }
    });
    updateMeetingMeta(data);
    setTag("vMeetingStatus", "RECORDING");
  }

  async function getMeetingDetail() {
    ensureMeeting();
    const data = await request("GET", `/meetings/${state.meetingId}`);
    updateMeetingMeta(data);
    setTag("vMeetingStatus", data.status || "UNKNOWN");
    return data;
  }

  async function listMine() {
    const data = await request("GET", "/meetings?tab=mine");
    summaryView.textContent = safeStringify(data);
  }

  async function pauseMeeting() {
    ensureMeeting();
    await request("POST", `/meetings/${state.meetingId}/pause`);
    state.isPaused = true;
    if (state.mediaRecorder && state.mediaRecorder.state === "recording") {
      state.mediaRecorder.pause();
    }
    setTag("vMeetingStatus", "PAUSED");
    setTag("vStreamStatus", "PAUSED");
  }

  async function resumeMeeting() {
    ensureMeeting();
    await request("POST", `/meetings/${state.meetingId}/resume`);
    state.isPaused = false;
    if (state.mediaRecorder && state.mediaRecorder.state === "paused") {
      state.mediaRecorder.resume();
    }
    setTag("vMeetingStatus", "RECORDING");
    setTag("vStreamStatus", "STREAMING");
  }

  async function finishMeeting() {
    ensureMeeting();
    await stopStreaming();
    const data = await request("POST", `/meetings/${state.meetingId}/finish`, {
      headers: { "Idempotency-Key": randomIdempotencyKey("web-finish") }
    });
    if (data.status) {
      setTag("vMeetingStatus", data.status);
    } else {
      setTag("vMeetingStatus", "PROCESSING");
    }
  }

  async function pollWorker() {
    await request("POST", "/admin/worker/run-once", { body: {} });
    const detail = await getMeetingDetail();
    if (detail.status) {
      setTag("vMeetingStatus", detail.status);
    }
  }

  async function getLive() {
    ensureMeeting();
    const data = await request("GET", `/meetings/${state.meetingId}/transcript-live`);
    transcriptView.textContent = safeStringify(data);
    if (data.task_status) {
      setTag("vMeetingStatus", data.task_status === "COMPLETED" ? "PROCESSING/COMPLETED" : data.task_status);
    }
  }

  async function getSummary() {
    ensureMeeting();
    try {
      const data = await request("GET", `/meetings/${state.meetingId}/summary`);
      summaryView.textContent = safeStringify(data);
      $("overviewInput").value = data.overview || "";
      $("chaptersInput").value = safeStringify(data.chapters || []);
      $("decisionsInput").value = safeStringify(data.decisions || []);
      $("highlightsInput").value = safeStringify(data.highlights || []);
      $("todosInput").value = safeStringify(data.todos || []);
      setTag("vMeetingStatus", "COMPLETED");
    } catch (e) {
      summaryView.textContent = String(e.message || e);
      throw e;
    }
  }

  async function patchSummary() {
    ensureMeeting();
    const payload = {
      overview: $("overviewInput").value,
      chapters: parseJsonOrEmpty($("chaptersInput").value, []),
      decisions: parseJsonOrEmpty($("decisionsInput").value, []),
      highlights: parseJsonOrEmpty($("highlightsInput").value, []),
      todos: parseJsonOrEmpty($("todosInput").value, [])
    };
    const data = await request("PATCH", `/meetings/${state.meetingId}/summary`, { body: payload });
    summaryView.textContent = safeStringify(data);
  }

  async function getPlaybackUrl() {
    ensureMeeting();
    const data = await request("GET", `/meetings/${state.meetingId}/audio-playback`);
    summaryView.textContent = safeStringify(data);
  }

  function parseJsonOrEmpty(text, fallback) {
    if (!text || !text.trim()) {
      return fallback;
    }
    try {
      return JSON.parse(text);
    } catch (_e) {
      return fallback;
    }
  }

  function ensureMeeting() {
    if (!state.meetingId) {
      throw new Error("请先创建会议");
    }
  }

  function pickRecorderMimeType() {
    const candidates = [
      "audio/webm;codecs=opus",
      "audio/webm",
      "audio/mp4",
      "audio/mpeg"
    ];
    for (const t of candidates) {
      if (window.MediaRecorder && MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported(t)) {
        return t;
      }
    }
    return "";
  }

  async function uploadAudioChunk(blob) {
    ensureMeeting();
    const idx = state.chunkIndex++;
    const form = new FormData();
    const filename = `chunk_${idx}.m4a`;
    form.append("file", blob, filename);
    const path = `/meetings/${state.meetingId}/audio-chunks?chunk_index=${idx}`;
    await request("POST", path, { formData: form });
    setText("vChunkStat", String(state.chunkIndex));
  }

  function mergeFloat32(buffers) {
    let total = 0;
    for (const b of buffers) {
      total += b.length;
    }
    const out = new Float32Array(total);
    let offset = 0;
    for (const b of buffers) {
      out.set(b, offset);
      offset += b.length;
    }
    return out;
  }

  function downsampleTo16k(input, inRate) {
    if (inRate === 16000) {
      return input;
    }
    const ratio = inRate / 16000;
    const outLen = Math.round(input.length / ratio);
    const out = new Float32Array(outLen);
    for (let i = 0; i < outLen; i++) {
      const idx = Math.min(Math.round(i * ratio), input.length - 1);
      out[i] = input[idx];
    }
    return out;
  }

  function floatToPcm16(input) {
    const buf = new ArrayBuffer(input.length * 2);
    const view = new DataView(buf);
    for (let i = 0; i < input.length; i++) {
      const s = Math.max(-1, Math.min(1, input[i]));
      view.setInt16(i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
    }
    return new Uint8Array(buf);
  }

  function sendUint8InChunks(ws, bytes, size) {
    for (let i = 0; i < bytes.length; i += size) {
      const chunk = bytes.subarray(i, i + size);
      ws.send(chunk);
    }
  }

  function generateSineFloat32(samples, sampleRate, freq, phase) {
    const out = new Float32Array(samples);
    const step = (2 * Math.PI * freq) / sampleRate;
    let p = phase;
    for (let i = 0; i < samples; i++) {
      out[i] = Math.sin(p) * 0.4;
      p += step;
      if (p > 2 * Math.PI) {
        p -= 2 * Math.PI;
      }
    }
    return { buffer: out, phase: p };
  }

  function pcm16ToWavBlob(pcm16, sampleRate) {
    const channels = 1;
    const bitsPerSample = 16;
    const byteRate = sampleRate * channels * bitsPerSample / 8;
    const blockAlign = channels * bitsPerSample / 8;
    const dataSize = pcm16.length * 2;
    const buffer = new ArrayBuffer(44 + dataSize);
    const view = new DataView(buffer);

    function writeStr(offset, str) {
      for (let i = 0; i < str.length; i++) {
        view.setUint8(offset + i, str.charCodeAt(i));
      }
    }

    writeStr(0, "RIFF");
    view.setUint32(4, 36 + dataSize, true);
    writeStr(8, "WAVE");
    writeStr(12, "fmt ");
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);
    view.setUint16(22, channels, true);
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, byteRate, true);
    view.setUint16(32, blockAlign, true);
    view.setUint16(34, bitsPerSample, true);
    writeStr(36, "data");
    view.setUint32(40, dataSize, true);

    let offset = 44;
    for (let i = 0; i < pcm16.length; i++) {
      view.setInt16(offset, pcm16[i], true);
      offset += 2;
    }
    return new Blob([buffer], { type: "audio/wav" });
  }

  function appendTranscriptLine(text) {
    const next = `[${now()}] ${text}\n`;
    transcriptView.textContent = (transcriptView.textContent + next).slice(-30000);
    transcriptView.scrollTop = transcriptView.scrollHeight;
  }

  function parseTranscriptionMessage(msg) {
    const out = [];
    if (!msg || typeof msg !== "object") {
      return out;
    }
    const payload = msg.payload || {};
    const candidates = [
      payload.result,
      payload?.stash_result?.text,
      payload?.output?.sentence?.text,
      payload?.output?.text,
      payload?.result?.text,
      payload.text
    ];
    for (const text of candidates) {
      if (typeof text === "string" && text.trim()) {
        out.push(text.trim());
      }
    }
    if (Array.isArray(payload?.output?.sentences)) {
      for (const s of payload.output.sentences) {
        if (s && typeof s.text === "string" && s.text.trim()) {
          out.push(s.text.trim());
        }
      }
    }
    return out;
  }

  function openWebSocket(joinUrl) {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(joinUrl);
      ws.binaryType = "arraybuffer";

      ws.onopen = () => {
        const cmd = {
          header: {
            name: "StartTranscription",
            namespace: "SpeechTranscriber"
          },
          payload: {
            format: "pcm",
            sample_rate: 16000
          }
        };
        ws.send(JSON.stringify(cmd));
        setTag("vWsStatus", "CONNECTED");
        log("WS", "已发送 StartTranscription", cmd);
        resolve(ws);
      };

      ws.onerror = (e) => {
        setTag("vWsStatus", "ERROR");
        reject(new Error(`WS连接失败: ${safeStringify(e)}`));
      };

      ws.onclose = () => {
        setTag("vWsStatus", "DISCONNECTED");
        log("WS", "连接已关闭");
      };

      ws.onmessage = (event) => {
        let payload;
        if (typeof event.data === "string") {
          try {
            payload = JSON.parse(event.data);
          } catch (_e) {
            payload = event.data;
          }
        } else {
          return;
        }
        const lines = parseTranscriptionMessage(payload);
        for (const line of lines) {
          appendTranscriptLine(line);
        }
      };
    });
  }

  async function initCapture() {
    state.stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false
      },
      video: false
    });

    const mime = pickRecorderMimeType();
    state.mediaRecorder = mime ? new MediaRecorder(state.stream, { mimeType: mime }) : new MediaRecorder(state.stream);
    state.mediaRecorder.ondataavailable = async (e) => {
      if (!e.data || e.data.size <= 0 || !state.meetingId) {
        return;
      }
      try {
        await uploadAudioChunk(e.data);
      } catch (err) {
        log("ERR", "音频分片上传失败", err.message || String(err));
      }
    };
    state.mediaRecorder.start(5000);

    state.audioContext = new AudioContext();
    if (state.audioContext.state === "suspended") {
      await state.audioContext.resume();
    }
    log("AUDIO", "麦克风音频上下文就绪", {
      state: state.audioContext.state,
      sample_rate: state.audioContext.sampleRate
    });
    state.sourceNode = state.audioContext.createMediaStreamSource(state.stream);
    state.processorNode = state.audioContext.createScriptProcessor(4096, 1, 1);
    state.processorNode.onaudioprocess = (e) => {
      if (!state.isStreaming || state.isPaused) {
        return;
      }
      const input = e.inputBuffer.getChannelData(0);
      state.pcmQueue.push(new Float32Array(input));
    };
    state.sourceNode.connect(state.processorNode);
    state.processorNode.connect(state.audioContext.destination);
  }

  function startSyntheticStreaming() {
    const freq = Number($("syntheticFreq").value || 440);
    const sampleRate = 16000;
    const frameSamples = 1600; // 100ms

    state.syntheticSendTimer = window.setInterval(() => {
      if (!state.isStreaming || state.isPaused || !state.ws || state.ws.readyState !== WebSocket.OPEN) {
        return;
      }
      const generated = generateSineFloat32(frameSamples, sampleRate, freq, state.syntheticPhase);
      state.syntheticPhase = generated.phase;
      const bytes = floatToPcm16(generated.buffer);
      sendUint8InChunks(state.ws, bytes, 1024);
    }, 100);

    state.syntheticUploadTimer = window.setInterval(async () => {
      if (!state.isStreaming || state.isPaused || !state.meetingId) {
        return;
      }
      try {
        const generated = generateSineFloat32(sampleRate, sampleRate, freq, state.syntheticPhase);
        state.syntheticPhase = generated.phase;
        const bytes = floatToPcm16(generated.buffer);
        const pcm16 = new Int16Array(bytes.buffer, bytes.byteOffset, bytes.byteLength / 2);
        const wavBlob = pcm16ToWavBlob(pcm16, sampleRate);
        await uploadAudioChunk(wavBlob);
      } catch (err) {
        log("ERR", "虚拟音源分片上传失败", err.message || String(err));
      }
    }, 5000);
  }

  function startMicrophonePcmPusher() {
    state.sendTimer = window.setInterval(() => {
      if (!state.isStreaming || state.isPaused || !state.ws || state.ws.readyState !== WebSocket.OPEN) {
        return;
      }
      if (!state.audioContext || state.pcmQueue.length === 0) {
        return;
      }
      const merged = mergeFloat32(state.pcmQueue);
      state.pcmQueue = [];
      const pcm16k = downsampleTo16k(merged, state.audioContext.sampleRate);
      const bytes = floatToPcm16(pcm16k);
      sendUint8InChunks(state.ws, bytes, 1024);
    }, 100);
  }

  async function startStreaming() {
    ensureMeeting();
    if (state.isStreaming) {
      log("WARN", "已在推流中");
      return;
    }
    state.joinUrl = $("joinUrl").value.trim() || state.joinUrl;
    if (!state.joinUrl) {
      throw new Error("MeetingJoinUrl 为空，无法连接听悟WS");
    }
    state.ws = await openWebSocket(state.joinUrl);
    const mode = $("streamMode").value;
    if (mode === "microphone") {
      await initCapture();
      startMicrophonePcmPusher();
    } else {
      startSyntheticStreaming();
    }

    state.isStreaming = true;
    state.isPaused = false;
    setTag("vStreamStatus", "STREAMING");
    log("OK", "推流已启动");
  }

  async function stopStreaming() {
    state.isStreaming = false;
    state.isPaused = false;
    setTag("vStreamStatus", "IDLE");

    if (state.sendTimer) {
      clearInterval(state.sendTimer);
      state.sendTimer = null;
    }
    if (state.syntheticSendTimer) {
      clearInterval(state.syntheticSendTimer);
      state.syntheticSendTimer = null;
    }
    if (state.syntheticUploadTimer) {
      clearInterval(state.syntheticUploadTimer);
      state.syntheticUploadTimer = null;
    }

    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      const cmd = {
        header: {
          name: "StopTranscription",
          namespace: "SpeechTranscriber"
        },
        payload: {}
      };
      state.ws.send(JSON.stringify(cmd));
      log("WS", "已发送 StopTranscription", cmd);
      await new Promise((r) => setTimeout(r, 300));
      state.ws.close();
    }
    state.ws = null;

    if (state.mediaRecorder) {
      if (state.mediaRecorder.state !== "inactive") {
        state.mediaRecorder.stop();
      }
      state.mediaRecorder = null;
    }

    if (state.processorNode) {
      try { state.processorNode.disconnect(); } catch (_e) {}
      state.processorNode = null;
    }
    if (state.sourceNode) {
      try { state.sourceNode.disconnect(); } catch (_e) {}
      state.sourceNode = null;
    }
    if (state.audioContext) {
      await state.audioContext.close();
      state.audioContext = null;
    }
    if (state.stream) {
      for (const track of state.stream.getTracks()) {
        track.stop();
      }
      state.stream = null;
    }
    state.pcmQueue = [];
    state.syntheticPhase = 0;
    setTag("vWsStatus", "DISCONNECTED");
    log("OK", "推流资源已释放");
  }

  async function createShare() {
    ensureMeeting();
    const days = Number($("shareExpireDays").value || 7);
    const passcode = $("sharePasscode").value.trim();
    const payload = { expire_days: days };
    if (passcode) {
      payload.passcode = passcode;
    }
    const data = await request("POST", `/meetings/${state.meetingId}/share-links`, {
      headers: { "Idempotency-Key": randomIdempotencyKey("web-share") },
      body: payload
    });
    state.shareToken = data.token || "";
    state.shareUrl = data.share_url || data.shareUrl || "";
    setText("vShareToken", state.shareToken);
    setText("vShareUrl", state.shareUrl);
    shareView.textContent = safeStringify(data);
  }

  async function verifyShare() {
    if (!state.shareToken) {
      throw new Error("请先创建分享链接");
    }
    const passcode = $("sharePasscode").value.trim();
    const data = await request("POST", `/share/${state.shareToken}/verify`, { body: { passcode } });
    shareView.textContent = safeStringify(data);
  }

  async function getShareContent() {
    if (!state.shareToken) {
      throw new Error("请先创建分享链接");
    }
    const passcode = $("sharePasscode").value.trim();
    const q = passcode ? `?passcode=${encodeURIComponent(passcode)}` : "";
    const data = await request("GET", `/share/${state.shareToken}/content${q}`);
    shareView.textContent = safeStringify(data);
  }

  async function revokeShare() {
    if (!state.shareToken) {
      throw new Error("请先创建分享链接");
    }
    const data = await request("POST", `/share/${state.shareToken}/revoke`);
    shareView.textContent = safeStringify(data);
  }

  function bind(id, fn) {
    $(id).addEventListener("click", async () => {
      try {
        await fn();
      } catch (e) {
        log("ERR", e.message || String(e));
      }
    });
  }

  function init() {
    $("baseUrl").value = window.location.origin;
    setTag("vMeetingStatus", "INIT");
    setTag("vWsStatus", "DISCONNECTED");
    setTag("vStreamStatus", "IDLE");
    setText("vChunkStat", "0");

    bind("btnHealth", healthCheck);
    bind("btnCreate", createMeeting);
    bind("btnListMine", listMine);
    bind("btnDetail", getMeetingDetail);

    bind("btnStartStream", startStreaming);
    bind("btnStopStream", stopStreaming);
    bind("btnPause", pauseMeeting);
    bind("btnResume", resumeMeeting);
    bind("btnFinish", finishMeeting);

    bind("btnPoll", pollWorker);
    bind("btnLive", getLive);
    bind("btnSummary", getSummary);
    bind("btnPatchSummary", patchSummary);
    bind("btnPlayback", getPlaybackUrl);

    bind("btnCreateShare", createShare);
    bind("btnVerifyShare", verifyShare);
    bind("btnGetShare", getShareContent);
    bind("btnRevokeShare", revokeShare);

    window.addEventListener("beforeunload", () => {
      stopStreaming().catch(() => {});
    });
    log("OK", "页面初始化完成");
  }

  init();
})();
