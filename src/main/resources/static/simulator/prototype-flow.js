/* eslint-disable no-console */
(() => {
  const $ = (id) => document.getElementById(id);

  const state = {
    currentView: "entry",
    viewStack: ["entry"],
    listTab: "all",
    meetings: [],
    meetingId: "",
    taskId: "",
    joinUrl: "",
    currentMeeting: null,
    summary: null,
    summarySection: "overview",
    editMode: false,
    shareToken: "",
    shareUrl: "",
    processingWatcherId: null,

    ws: null,
    stream: null,
    mediaRecorder: null,
    audioContext: null,
    sourceNode: null,
    processorNode: null,
    pcmQueue: [],

    isStreaming: false,
    isPaused: false,
    chunkIndex: 0,
    elapsedSec: 0,

    timers: {
      recordTicker: null,
      syntheticSend: null,
      syntheticUpload: null,
      microphoneSend: null
    },

    syntheticPhase: 0
  };

  const logView = $("logView");
  const recordTranscript = $("recordTranscript");
  const summaryContent = $("summaryContent");

  function nowTime() {
    return new Date().toLocaleTimeString();
  }

  function log(level, message, data) {
    const head = `[${nowTime()}][${level}] ${message}`;
    const body = data === undefined ? "" : `\n${safeStringify(data)}`;
    logView.textContent = `${head}${body}\n${logView.textContent}`.slice(0, 120000);
  }

  function safeStringify(value) {
    try {
      return JSON.stringify(value, null, 2);
    } catch (_e) {
      return String(value);
    }
  }

  function pick(obj, ...keys) {
    if (!obj || typeof obj !== "object") {
      return null;
    }
    for (const key of keys) {
      if (obj[key] !== undefined && obj[key] !== null) {
        return obj[key];
      }
    }
    return null;
  }

  function cfg() {
    const baseRaw = $("cfgBaseUrl").value.trim() || window.location.origin;
    const prefixRaw = $("cfgApiPrefix").value.trim() || "/api/v1";
    return {
      baseUrl: baseRaw.replace(/\/+$/, ""),
      apiPrefix: prefixRaw.startsWith("/") ? prefixRaw : `/${prefixRaw}`,
      userId: $("cfgUserId").value.trim() || "web_user_001",
      titlePrefix: $("cfgTitlePrefix").value.trim() || "原型流程模拟会议",
      streamMode: $("cfgStreamMode").value,
      syntheticFreq: Number($("cfgSyntheticFreq").value || 440)
    };
  }

  function requestHeaders(extra) {
    const headers = extra ? { ...extra } : {};
    headers["X-User-Id"] = cfg().userId;
    return headers;
  }

  async function request(method, path, options) {
    const opts = options || {};
    const c = cfg();
    const url = `${c.baseUrl}${c.apiPrefix}${path}`;
    const init = { method, headers: requestHeaders(opts.headers) };

    if (opts.formData) {
      init.body = opts.formData;
      delete init.headers["Content-Type"];
    } else if (opts.body !== undefined) {
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(opts.body);
    }

    const resp = await fetch(url, init);
    const text = await resp.text();
    let data;
    try {
      data = text ? JSON.parse(text) : {};
    } catch (_e) {
      data = text;
    }

    log("HTTP", `${method} ${path} -> ${resp.status}`, data);

    if (!resp.ok) {
      const detail = typeof data === "object"
        ? (data.detail || data.message || safeStringify(data))
        : String(data || "request failed");
      throw new Error(`HTTP ${resp.status}: ${detail}`);
    }

    return data;
  }

  async function healthCheck() {
    const c = cfg();
    const resp = await fetch(`${c.baseUrl}/healthz`);
    const data = await resp.json();
    log("OK", "健康检查通过", data);
    return data;
  }

  function randomIdempotencyKey(prefix) {
    return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
  }

  function randomMessageId() {
    return crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
  }

  function toDateTimeText(value) {
    if (!value) {
      return "-";
    }
    if (Array.isArray(value) && value.length >= 6) {
      const [y, mon, d, h, m, s] = value;
      const pad = (n) => String(n).padStart(2, "0");
      return `${y}-${pad(mon)}-${pad(d)} ${pad(h)}:${pad(m)}:${pad(s)}`;
    }
    if (typeof value === "string") {
      const dt = new Date(value);
      if (!Number.isNaN(dt.getTime())) {
        return `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, "0")}-${String(dt.getDate()).padStart(2, "0")} ${String(dt.getHours()).padStart(2, "0")}:${String(dt.getMinutes()).padStart(2, "0")}:${String(dt.getSeconds()).padStart(2, "0")}`;
      }
      return value;
    }
    return String(value);
  }

  function statusClass(status) {
    return `status-${status || "PROCESSING"}`;
  }

  function statusText(status) {
    if (!status) {
      return "UNKNOWN";
    }
    return status;
  }

  function setDebugKv() {
    $("kvMeetingId").textContent = state.meetingId || "-";
    $("kvTaskId").textContent = state.taskId || "-";
    $("kvMeetingStatus").textContent = pick(state.currentMeeting, "status") || "-";
    $("kvWsStatus").textContent = state.ws && state.ws.readyState === WebSocket.OPEN ? "CONNECTED" : "DISCONNECTED";
    $("kvStreamStatus").textContent = state.isStreaming ? (state.isPaused ? "PAUSED" : "STREAMING") : "IDLE";
    $("kvChunkCount").textContent = String(state.chunkIndex);
  }

  function setNavByView() {
    const titleMap = {
      entry: "AI听会入口",
      list: "AI听会",
      record: "AI听会",
      summary: "会议纪要"
    };
    $("navTitle").textContent = titleMap[state.currentView] || "AI听会";
    $("btnNavBack").disabled = state.currentView === "entry";
  }

  function switchView(view, push = true) {
    const all = ["entry", "list", "record", "summary"];
    for (const key of all) {
      const el = $(`view-${key}`);
      el.classList.toggle("active", key === view);
    }

    state.currentView = view;
    if (push) {
      const current = state.viewStack[state.viewStack.length - 1];
      if (current !== view) {
        state.viewStack.push(view);
      }
    }
    setNavByView();
  }

  function popView() {
    if (state.viewStack.length <= 1) {
      return;
    }
    state.viewStack.pop();
    const last = state.viewStack[state.viewStack.length - 1] || "entry";
    switchView(last, false);
  }

  function ensureMeetingSelected() {
    if (!state.meetingId) {
      throw new Error("请先选择或创建会议");
    }
  }

  function clearProcessingWatcher() {
    if (state.processingWatcherId) {
      clearInterval(state.processingWatcherId);
      state.processingWatcherId = null;
    }
  }

  function startRecordTicker() {
    if (state.timers.recordTicker) {
      clearInterval(state.timers.recordTicker);
    }
    state.timers.recordTicker = window.setInterval(() => {
      if (state.isStreaming && !state.isPaused) {
        state.elapsedSec += 1;
        renderRecordTimer();
      }
    }, 1000);
  }

  function stopRecordTicker() {
    if (state.timers.recordTicker) {
      clearInterval(state.timers.recordTicker);
      state.timers.recordTicker = null;
    }
  }

  function renderRecordTimer() {
    const sec = state.elapsedSec;
    const mm = String(Math.floor(sec / 60)).padStart(2, "0");
    const ss = String(sec % 60).padStart(2, "0");
    $("recordTimer").textContent = `${mm}:${ss}（录音加密保护中）`;
  }

  async function loadMeetings(tab) {
    if (tab) {
      state.listTab = tab;
    }
    const data = await request("GET", `/meetings?tab=${encodeURIComponent(state.listTab)}`);
    state.meetings = Array.isArray(data) ? data : [];
    renderMeetingList();
    return state.meetings;
  }

  function renderMeetingList() {
    const box = $("meetingList");
    box.innerHTML = "";

    if (!state.meetings.length) {
      box.innerHTML = '<div class="empty">暂无听会记录</div>';
      return;
    }

    const userId = cfg().userId;

    for (const meeting of state.meetings) {
      const id = pick(meeting, "id");
      const title = pick(meeting, "title") || "未命名录音";
      const status = statusText(pick(meeting, "status"));
      const ownerId = pick(meeting, "owner_id", "ownerId") || "-";
      const startedAt = toDateTimeText(pick(meeting, "started_at", "startedAt"));
      const progress = pick(meeting, "processing_progress", "processingProgress");

      const item = document.createElement("div");
      item.className = "meeting-item";

      const statusLabel = status === "PROCESSING" && typeof progress === "number"
        ? `${status} ${progress}%`
        : status;

      item.innerHTML = `
        <div class="meeting-title">${escapeHtml(title)}</div>
        <div class="meeting-meta">
          <span>${escapeHtml(ownerId)} ｜ ${escapeHtml(startedAt)}</span>
          <span class="status-badge ${statusClass(status)}">${escapeHtml(statusLabel)}</span>
        </div>
        <div class="hint">点击进入详情；长按可删除（仅自己创建）</div>
      `;

      let holdTimer = null;
      let longPressed = false;

      item.addEventListener("pointerdown", () => {
        if (ownerId !== userId) {
          return;
        }
        longPressed = false;
        holdTimer = window.setTimeout(async () => {
          longPressed = true;
          if (!confirm(`确认删除会议：${title}？`)) {
            return;
          }
          try {
            await request("DELETE", `/meetings/${id}`);
            if (state.meetingId === id) {
              await cleanupStreamingResources();
              state.meetingId = "";
              state.taskId = "";
              state.joinUrl = "";
              state.currentMeeting = null;
            }
            await loadMeetings(state.listTab);
          } catch (err) {
            log("ERR", "删除会议失败", err.message || String(err));
          }
        }, 800);
      });

      const cancelHold = () => {
        if (holdTimer) {
          clearTimeout(holdTimer);
          holdTimer = null;
        }
      };

      item.addEventListener("pointerup", cancelHold);
      item.addEventListener("pointerleave", cancelHold);
      item.addEventListener("pointercancel", cancelHold);

      item.addEventListener("click", async () => {
        if (longPressed) {
          longPressed = false;
          return;
        }
        try {
          await openMeetingById(id);
        } catch (err) {
          log("ERR", "打开会议失败", err.message || String(err));
        }
      });

      box.appendChild(item);
    }
  }

  async function openMeetingById(meetingId) {
    state.meetingId = meetingId;
    await syncMeetingDetail();

    const status = pick(state.currentMeeting, "status");
    if (status === "COMPLETED" || status === "PROCESSING" || status === "FAILED") {
      await openSummaryView();
      return;
    }

    await openRecordView(false);
  }

  async function createMeeting() {
    const c = cfg();
    const title = `${c.titlePrefix}_${new Date().toISOString().replace(/[-:TZ.]/g, "").slice(0, 14)}`;

    const data = await request("POST", "/meetings", {
      headers: { "Idempotency-Key": randomIdempotencyKey("prototype-create") },
      body: { title }
    });

    state.meetingId = pick(data, "meeting_id", "meetingId") || "";
    state.taskId = pick(data, "task_id", "taskId") || "";
    state.joinUrl = pick(data, "meeting_join_url", "meetingJoinUrl") || "";
    state.currentMeeting = {
      id: state.meetingId,
      title,
      status: pick(data, "status") || "RECORDING",
      startedAt: new Date().toISOString(),
      meetingJoinUrl: state.joinUrl,
      taskId: state.taskId
    };

    state.summary = null;
    state.shareToken = "";
    state.shareUrl = "";
    state.chunkIndex = 0;
    state.elapsedSec = 0;
    state.syntheticPhase = 0;
    recordTranscript.textContent = "";
    renderRecordTimer();
    setDebugKv();

    log("OK", "会议创建成功", {
      meeting_id: state.meetingId,
      task_id: state.taskId
    });
  }

  async function syncMeetingDetail() {
    ensureMeetingSelected();
    const detail = await request("GET", `/meetings/${state.meetingId}`);
    state.currentMeeting = detail;
    state.taskId = pick(detail, "task_id", "taskId") || state.taskId;
    state.joinUrl = pick(detail, "meeting_join_url", "meetingJoinUrl") || state.joinUrl;
    setDebugKv();
    return detail;
  }

  async function openListView() {
    switchView("list");
    updateTabButtons();
    await loadMeetings(state.listTab);
    setDebugKv();
  }

  function updateTabButtons() {
    const tabs = $("listTabs").querySelectorAll(".tab");
    for (const tab of tabs) {
      tab.classList.toggle("active", tab.dataset.tab === state.listTab);
    }
  }

  function applyRecordUi() {
    const title = pick(state.currentMeeting, "title") || "新录音";
    const owner = pick(state.currentMeeting, "owner_id", "ownerId") || cfg().userId;
    const startedAt = toDateTimeText(pick(state.currentMeeting, "started_at", "startedAt"));
    const status = statusText(pick(state.currentMeeting, "status"));

    $("recordTitle").textContent = title;
    $("recordSub").textContent = `${owner} ｜ ${startedAt} ｜ ${status}`;

    const pauseBtn = $("btnRecordPauseResume");
    if (state.isPaused) {
      pauseBtn.textContent = "继续";
      pauseBtn.className = "btn-resume";
    } else {
      pauseBtn.textContent = "暂停";
      pauseBtn.className = "btn-pause";
    }
  }

  async function openRecordView(autoStart) {
    if (!state.currentMeeting) {
      await syncMeetingDetail();
    }
    switchView("record");
    applyRecordUi();
    renderRecordTimer();

    if (autoStart) {
      await startStreaming();
    }
  }

  function parseTranscriptionMessage(msg) {
    const lines = [];
    if (!msg || typeof msg !== "object") {
      return lines;
    }

    const payload = pick(msg, "payload") || {};
    const candidates = [
      payload.result,
      payload?.stash_result?.text,
      payload?.output?.sentence?.text,
      payload?.output?.text,
      payload?.result?.text
    ];

    for (const text of candidates) {
      if (typeof text === "string" && text.trim()) {
        lines.push(text.trim());
      }
    }

    const outputSentences = payload?.output?.sentences;
    if (Array.isArray(outputSentences)) {
      for (const sentence of outputSentences) {
        if (sentence && typeof sentence.text === "string" && sentence.text.trim()) {
          lines.push(sentence.text.trim());
        }
      }
    }

    return lines;
  }

  function appendTranscriptLine(text) {
    const line = `[${nowTime()}] ${text}\n`;
    recordTranscript.textContent = (recordTranscript.textContent + line).slice(-50000);
    recordTranscript.scrollTop = recordTranscript.scrollHeight;
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
        log("WS", "已发送 StartTranscription", cmd);
        resolve(ws);
      };

      ws.onerror = (event) => {
        reject(new Error(`WS连接失败: ${safeStringify(event)}`));
      };

      ws.onclose = () => {
        log("WS", "连接已关闭");
        setDebugKv();
      };

      ws.onmessage = (event) => {
        if (typeof event.data !== "string") {
          return;
        }
        try {
          const msg = JSON.parse(event.data);
          const lines = parseTranscriptionMessage(msg);
          for (const line of lines) {
            appendTranscriptLine(line);
          }
        } catch (_e) {
        }
      };
    });
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
    ensureMeetingSelected();
    const idx = state.chunkIndex++;
    const form = new FormData();
    form.append("file", blob, `chunk_${idx}.m4a`);

    await request("POST", `/meetings/${state.meetingId}/audio-chunks?chunk_index=${idx}`, {
      formData: form
    });
    setDebugKv();
  }

  function mergeFloat32(buffers) {
    let total = 0;
    for (const buf of buffers) {
      total += buf.length;
    }
    const out = new Float32Array(total);
    let offset = 0;
    for (const buf of buffers) {
      out.set(buf, offset);
      offset += buf.length;
    }
    return out;
  }

  function downsampleTo16k(input, inputSampleRate) {
    if (inputSampleRate === 16000) {
      return input;
    }

    const ratio = inputSampleRate / 16000;
    const outputLength = Math.round(input.length / ratio);
    const output = new Float32Array(outputLength);
    for (let i = 0; i < outputLength; i++) {
      const sourceIndex = Math.min(Math.round(i * ratio), input.length - 1);
      output[i] = input[sourceIndex];
    }
    return output;
  }

  function floatToPcm16(input) {
    const buffer = new ArrayBuffer(input.length * 2);
    const view = new DataView(buffer);
    for (let i = 0; i < input.length; i++) {
      const sample = Math.max(-1, Math.min(1, input[i]));
      view.setInt16(i * 2, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true);
    }
    return new Uint8Array(buffer);
  }

  function sendUint8InChunks(ws, bytes, packetSize) {
    for (let i = 0; i < bytes.length; i += packetSize) {
      ws.send(bytes.subarray(i, i + packetSize));
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

    const writeString = (offset, text) => {
      for (let i = 0; i < text.length; i++) {
        view.setUint8(offset + i, text.charCodeAt(i));
      }
    };

    writeString(0, "RIFF");
    view.setUint32(4, 36 + dataSize, true);
    writeString(8, "WAVE");
    writeString(12, "fmt ");
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);
    view.setUint16(22, channels, true);
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, byteRate, true);
    view.setUint16(32, blockAlign, true);
    view.setUint16(34, bitsPerSample, true);
    writeString(36, "data");
    view.setUint32(40, dataSize, true);

    let offset = 44;
    for (let i = 0; i < pcm16.length; i++) {
      view.setInt16(offset, pcm16[i], true);
      offset += 2;
    }

    return new Blob([buffer], { type: "audio/wav" });
  }

  async function initMicrophoneCapture() {
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
    state.mediaRecorder = mime
      ? new MediaRecorder(state.stream, { mimeType: mime })
      : new MediaRecorder(state.stream);

    state.mediaRecorder.ondataavailable = async (event) => {
      if (!event.data || event.data.size <= 0 || !state.meetingId || !state.isStreaming || state.isPaused) {
        return;
      }
      try {
        await uploadAudioChunk(event.data);
      } catch (err) {
        log("ERR", "麦克风分片上传失败", err.message || String(err));
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

    state.processorNode.onaudioprocess = (event) => {
      if (!state.isStreaming || state.isPaused) {
        return;
      }
      const input = event.inputBuffer.getChannelData(0);
      state.pcmQueue.push(new Float32Array(input));
    };

    state.sourceNode.connect(state.processorNode);
    state.processorNode.connect(state.audioContext.destination);

    state.timers.microphoneSend = window.setInterval(() => {
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

  function startSyntheticMode() {
    const { syntheticFreq } = cfg();
    const sampleRate = 16000;
    const frameSamples = 1600;

    state.timers.syntheticSend = window.setInterval(() => {
      if (!state.isStreaming || state.isPaused || !state.ws || state.ws.readyState !== WebSocket.OPEN) {
        return;
      }
      const generated = generateSineFloat32(frameSamples, sampleRate, syntheticFreq, state.syntheticPhase);
      state.syntheticPhase = generated.phase;
      const bytes = floatToPcm16(generated.buffer);
      sendUint8InChunks(state.ws, bytes, 1024);
    }, 100);

    state.timers.syntheticUpload = window.setInterval(async () => {
      if (!state.isStreaming || state.isPaused || !state.meetingId) {
        return;
      }
      try {
        const generated = generateSineFloat32(sampleRate, sampleRate, syntheticFreq, state.syntheticPhase);
        state.syntheticPhase = generated.phase;
        const bytes = floatToPcm16(generated.buffer);
        const pcm16 = new Int16Array(bytes.buffer, bytes.byteOffset, bytes.byteLength / 2);
        const wavBlob = pcm16ToWavBlob(pcm16, sampleRate);
        await uploadAudioChunk(wavBlob);
      } catch (err) {
        log("ERR", "虚拟音源上传失败", err.message || String(err));
      }
    }, 5000);
  }

  async function startStreaming() {
    ensureMeetingSelected();

    if (state.isStreaming) {
      return;
    }

    if (!state.joinUrl) {
      await syncMeetingDetail();
    }

    if (!state.joinUrl) {
      throw new Error("MeetingJoinUrl为空，无法开始推流");
    }

    state.ws = await openWebSocket(state.joinUrl);

    state.isStreaming = true;
    state.isPaused = false;

    const mode = cfg().streamMode;
    if (mode === "microphone") {
      await initMicrophoneCapture();
    } else {
      startSyntheticMode();
    }

    startRecordTicker();
    applyRecordUi();
    setDebugKv();
    log("OK", "推流已启动", { mode });
  }

  async function cleanupStreamingResources() {
    if (state.timers.syntheticSend) {
      clearInterval(state.timers.syntheticSend);
      state.timers.syntheticSend = null;
    }
    if (state.timers.syntheticUpload) {
      clearInterval(state.timers.syntheticUpload);
      state.timers.syntheticUpload = null;
    }
    if (state.timers.microphoneSend) {
      clearInterval(state.timers.microphoneSend);
      state.timers.microphoneSend = null;
    }

    if (state.mediaRecorder) {
      if (state.mediaRecorder.state !== "inactive") {
        state.mediaRecorder.stop();
      }
      state.mediaRecorder = null;
    }

    if (state.processorNode) {
      try {
        state.processorNode.disconnect();
      } catch (_e) {
      }
      state.processorNode = null;
    }

    if (state.sourceNode) {
      try {
        state.sourceNode.disconnect();
      } catch (_e) {
      }
      state.sourceNode = null;
    }

    if (state.audioContext) {
      try {
        await state.audioContext.close();
      } catch (_e) {
      }
      state.audioContext = null;
    }

    if (state.stream) {
      const tracks = state.stream.getTracks ? state.stream.getTracks() : [];
      for (const track of tracks) {
        track.stop();
      }
      state.stream = null;
    }

    state.pcmQueue = [];
    state.syntheticPhase = 0;
  }

  async function stopStreaming() {
    if (!state.isStreaming && !state.ws) {
      await cleanupStreamingResources();
      return;
    }

    state.isStreaming = false;
    state.isPaused = false;

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
      await new Promise((resolve) => setTimeout(resolve, 300));
      state.ws.close();
    }

    state.ws = null;
    stopRecordTicker();
    await cleanupStreamingResources();
    applyRecordUi();
    setDebugKv();
  }

  async function pauseOrResumeMeeting() {
    ensureMeetingSelected();

    if (!state.isStreaming) {
      await startStreaming();
      return;
    }

    if (!state.isPaused) {
      await request("POST", `/meetings/${state.meetingId}/pause`);
      state.isPaused = true;
      if (state.mediaRecorder && state.mediaRecorder.state === "recording") {
        state.mediaRecorder.pause();
      }
      if (state.currentMeeting) {
        state.currentMeeting.status = "PAUSED";
      }
      log("OK", "会议已暂停");
    } else {
      await request("POST", `/meetings/${state.meetingId}/resume`);
      state.isPaused = false;
      if (state.mediaRecorder && state.mediaRecorder.state === "paused") {
        state.mediaRecorder.resume();
      }
      if (state.currentMeeting) {
        state.currentMeeting.status = "RECORDING";
      }
      log("OK", "会议已继续");
    }

    applyRecordUi();
    setDebugKv();
  }

  async function finishMeetingFromRecord() {
    ensureMeetingSelected();
    await stopStreaming();

    const data = await request("POST", `/meetings/${state.meetingId}/finish`, {
      headers: { "Idempotency-Key": randomIdempotencyKey("prototype-finish") }
    });

    if (state.currentMeeting) {
      state.currentMeeting.status = pick(data, "status") || "PROCESSING";
    }
    setDebugKv();
    log("OK", "会议已结束，进入处理中", data);

    startProcessingWatcher(state.meetingId);
    await openListView();
  }

  async function triggerWorkerRunOnce() {
    try {
      const data = await request("POST", "/admin/worker/run-once");
      log("OK", "Worker已触发", data);
      return data;
    } catch (err) {
      log("WARN", "Worker触发失败", err.message || String(err));
      return null;
    }
  }

  function startProcessingWatcher(meetingId) {
    clearProcessingWatcher();
    state.processingWatcherId = window.setInterval(async () => {
      try {
        if (!meetingId) {
          return;
        }

        const detail = await request("GET", `/meetings/${meetingId}`);
        const status = pick(detail, "status");

        if (status === "PROCESSING") {
          await triggerWorkerRunOnce();
          return;
        }

        if (status === "COMPLETED" || status === "FAILED" || status === "DELETED") {
          clearProcessingWatcher();
          if (state.currentView === "list") {
            await loadMeetings(state.listTab);
          }
          if (state.currentView === "summary" && state.meetingId === meetingId) {
            await refreshSummary();
          }
        }
      } catch (err) {
        log("WARN", "处理轮询失败", err.message || String(err));
      }
    }, 8000);
  }

  function escapeHtml(text) {
    const str = text == null ? "" : String(text);
    return str
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/\"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

  function valueToText(value) {
    if (value == null) {
      return "";
    }
    if (typeof value === "string") {
      return value;
    }
    if (typeof value === "number" || typeof value === "boolean") {
      return String(value);
    }
    if (Array.isArray(value)) {
      return value.map((item) => valueToText(item)).filter(Boolean).join("；");
    }
    if (typeof value === "object") {
      const preferred = [
        "title", "topic", "name", "speaker", "time", "timestamp", "decision",
        "action", "owner", "due", "deadline", "summary", "content", "text", "description"
      ];
      const parts = [];
      for (const key of preferred) {
        if (value[key] !== undefined && value[key] !== null && String(value[key]).trim() !== "") {
          parts.push(`${key}: ${valueToText(value[key])}`);
        }
      }
      if (parts.length > 0) {
        return parts.join(" ｜ ");
      }
      const firstKeys = Object.keys(value).slice(0, 6);
      return firstKeys.map((key) => `${key}: ${valueToText(value[key])}`).join(" ｜ ");
    }
    return String(value);
  }

  function listToHtml(items, emptyText) {
    if (!Array.isArray(items) || items.length === 0) {
      return `<div class="empty">${escapeHtml(emptyText)}</div>`;
    }

    const li = items
      .map((item) => `<li>${escapeHtml(valueToText(item) || "-")}</li>`)
      .join("");
    return `<ul>${li}</ul>`;
  }

  function renderSummaryBody() {
    const summary = state.summary;
    const section = state.summarySection;

    if (!summary) {
      summaryContent.innerHTML = '<div class="empty">暂无纪要，请刷新或等待处理完成。</div>';
      return;
    }

    if (section === "overview") {
      const overview = pick(summary, "overview") || "";
      if (!overview.trim()) {
        summaryContent.innerHTML = '<div class="empty">暂无图文总结</div>';
      } else {
        summaryContent.innerHTML = `<div>${escapeHtml(overview).replace(/\n/g, "<br>")}</div>`;
      }
      return;
    }

    if (section === "chapters") {
      summaryContent.innerHTML = listToHtml(pick(summary, "chapters") || [], "暂无智能章节");
      return;
    }

    if (section === "decisions") {
      summaryContent.innerHTML = listToHtml(pick(summary, "decisions") || [], "暂无关键决策");
      return;
    }

    if (section === "highlights") {
      summaryContent.innerHTML = listToHtml(pick(summary, "highlights") || [], "暂无金句时刻");
      return;
    }

    if (section === "todos") {
      const todos = pick(summary, "todos") || [];
      if (!Array.isArray(todos) || todos.length === 0) {
        summaryContent.innerHTML = '<div class="empty">暂无待办事项</div>';
        return;
      }
      const rows = todos.map((item) => `<li>[ ] ${escapeHtml(valueToText(item) || "-")}</li>`).join("");
      summaryContent.innerHTML = `<ul>${rows}</ul>`;
    }
  }

  function renderSummaryMeta() {
    const meeting = state.currentMeeting || {};
    const summary = state.summary || {};

    $("summaryTitle").textContent = `AI听会智能纪要：${pick(meeting, "title") || "-"}`;

    const owner = pick(meeting, "owner_id", "ownerId") || cfg().userId;
    const updated = toDateTimeText(pick(summary, "updated_at", "updatedAt", "created_at", "createdAt"));
    $("summaryMeta").textContent = `${owner} ｜ ${updated}`;

    const status = statusText(pick(meeting, "status"));
    const badge = $("summaryStatus");
    badge.className = `status-badge ${statusClass(status)}`;
    badge.textContent = status;
  }

  function renderSummaryTabs() {
    const tabs = $("summaryTabs").querySelectorAll(".section-tab");
    for (const tab of tabs) {
      tab.classList.toggle("active", tab.dataset.section === state.summarySection);
    }
  }

  function setEditMode(enabled) {
    state.editMode = enabled;
    $("editPanel").classList.toggle("active", enabled);
    $("btnSummaryEdit").textContent = enabled ? "退出编辑" : "在线编辑";

    if (enabled && state.summary) {
      $("editOverview").value = pick(state.summary, "overview") || "";
      $("editChapters").value = safeStringify(pick(state.summary, "chapters") || []);
      $("editDecisions").value = safeStringify(pick(state.summary, "decisions") || []);
      $("editHighlights").value = safeStringify(pick(state.summary, "highlights") || []);
      $("editTodos").value = safeStringify(pick(state.summary, "todos") || []);
    }
  }

  function parseJsonOrFallback(text, fallback) {
    if (!text || !text.trim()) {
      return fallback;
    }
    try {
      return JSON.parse(text);
    } catch (_e) {
      return fallback;
    }
  }

  async function saveSummaryEdit() {
    ensureMeetingSelected();
    const payload = {
      overview: $("editOverview").value,
      chapters: parseJsonOrFallback($("editChapters").value, []),
      decisions: parseJsonOrFallback($("editDecisions").value, []),
      highlights: parseJsonOrFallback($("editHighlights").value, []),
      todos: parseJsonOrFallback($("editTodos").value, [])
    };

    const data = await request("PATCH", `/meetings/${state.meetingId}/summary`, { body: payload });
    state.summary = data;
    renderSummaryMeta();
    renderSummaryBody();
    setEditMode(false);
    log("OK", "纪要编辑已保存", { version: pick(data, "version") });
  }

  async function refreshSummary() {
    ensureMeetingSelected();
    const detail = await syncMeetingDetail();

    try {
      state.summary = await request("GET", `/meetings/${state.meetingId}/summary`);
    } catch (err) {
      if ((err.message || "").includes("404")) {
        state.summary = null;
      } else {
        throw err;
      }
    }

    renderSummaryTabs();
    renderSummaryMeta();
    renderSummaryBody();
    if (state.editMode) {
      setEditMode(true);
    }

    const status = pick(detail, "status");
    if (status === "PROCESSING") {
      startProcessingWatcher(state.meetingId);
    }
    setDebugKv();
  }

  async function openSummaryView() {
    switchView("summary");
    renderSummaryTabs();
    await refreshSummary();
  }

  async function playAudio() {
    ensureMeetingSelected();
    const data = await request("GET", `/meetings/${state.meetingId}/audio-playback`);
    const audioUrl = pick(data, "url");
    if (!audioUrl) {
      throw new Error("回放地址为空");
    }
    const player = $("audioPlayer");
    player.src = audioUrl;
    player.play().catch(() => {});
    log("OK", "已加载回放地址", data);
  }

  function openKnowledgeDrawer() {
    const links = Array.isArray(pick(state.summary, "knowledge_links", "knowledgeLinks"))
      ? pick(state.summary, "knowledge_links", "knowledgeLinks")
      : [];

    const list = $("knowledgeList");
    if (!links.length) {
      list.innerHTML = '<div class="empty">暂无智库关联结果</div>';
    } else {
      list.innerHTML = "";
      for (const item of links) {
        const title = valueToText(item.title || item.name || item.topic || "关联内容");
        const desc = valueToText(item.summary || item.description || item.snippet || item.content || "");
        const link = item.url || item.link || item.href || "";

        const block = document.createElement("div");
        block.className = "knowledge-item";
        block.innerHTML = `
          <div class="knowledge-title">${escapeHtml(title)}</div>
          <div class="knowledge-desc">${escapeHtml(desc)}</div>
          ${link ? `<a class="knowledge-link" href="${escapeHtml(link)}" target="_blank" rel="noreferrer">${escapeHtml(link)}</a>` : ""}
        `;
        list.appendChild(block);
      }
    }

    $("overlay").classList.add("active");
    $("knowledgeDrawer").classList.add("active");
  }

  function closeKnowledgeDrawer() {
    $("knowledgeDrawer").classList.remove("active");
    if (!$("shareSheet").classList.contains("active")) {
      $("overlay").classList.remove("active");
    }
  }

  function openShareSheet() {
    $("overlay").classList.add("active");
    $("shareSheet").classList.add("active");
    $("shareToken").value = state.shareToken || "";
    $("shareUrl").value = state.shareUrl || "";
  }

  function closeShareSheet() {
    $("shareSheet").classList.remove("active");
    if (!$("knowledgeDrawer").classList.contains("active")) {
      $("overlay").classList.remove("active");
    }
  }

  async function createShareLink() {
    ensureMeetingSelected();
    const expireDays = Number($("shareExpire").value || 7);
    const passcode = $("sharePasscode").value.trim();

    const payload = { expire_days: expireDays };
    if (passcode) {
      payload.passcode = passcode;
    }

    const data = await request("POST", `/meetings/${state.meetingId}/share-links`, {
      headers: { "Idempotency-Key": randomIdempotencyKey("prototype-share") },
      body: payload
    });

    state.shareToken = pick(data, "token") || "";
    state.shareUrl = pick(data, "share_url", "shareUrl") || "";

    $("shareToken").value = state.shareToken;
    $("shareUrl").value = state.shareUrl;
    log("OK", "分享链接创建成功", data);
  }

  async function copyShareLink() {
    const text = [state.shareUrl, state.shareToken ? `提取码：${$("sharePasscode").value.trim()}` : ""]
      .filter(Boolean)
      .join("\n");

    if (!text) {
      throw new Error("请先创建分享链接");
    }

    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(text);
    } else {
      const ta = document.createElement("textarea");
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand("copy");
      document.body.removeChild(ta);
    }

    log("OK", "分享链接已复制");
  }

  function ensureShareToken() {
    const token = state.shareToken || $("shareToken").value.trim();
    if (!token) {
      throw new Error("请先创建分享链接");
    }
    return token;
  }

  async function verifyShare() {
    const token = ensureShareToken();
    const passcode = $("sharePasscode").value.trim();
    const data = await request("POST", `/share/${token}/verify`, { body: { passcode } });
    log("OK", "分享提取码验证结果", data);
  }

  async function getShareContent() {
    const token = ensureShareToken();
    const passcode = $("sharePasscode").value.trim();
    const q = passcode ? `?passcode=${encodeURIComponent(passcode)}` : "";
    const data = await request("GET", `/share/${token}/content${q}`);
    log("OK", "分享内容读取成功", data);
  }

  async function revokeShare() {
    const token = ensureShareToken();
    const data = await request("POST", `/share/${token}/revoke`);
    log("OK", "分享链接已撤销", data);
  }

  async function enterListFromEntry() {
    switchView("list");
    updateTabButtons();
    await loadMeetings("all");
  }

  async function startMeetingFromList() {
    await createMeeting();
    await openRecordView(true);
  }

  async function handleNavBack() {
    if (state.currentView === "entry") {
      return;
    }

    if (state.currentView === "record" && state.isStreaming) {
      const ok = confirm("返回列表会停止前端推流，但不会结束会议。是否继续？");
      if (!ok) {
        return;
      }
      await stopStreaming();
    }

    popView();

    if (state.currentView === "list") {
      await loadMeetings(state.listTab);
    }
  }

  async function refreshCurrentView() {
    if (state.currentView === "entry") {
      await healthCheck();
      return;
    }
    if (state.currentView === "list") {
      await loadMeetings(state.listTab);
      return;
    }
    if (state.currentView === "record") {
      await syncMeetingDetail();
      return;
    }
    if (state.currentView === "summary") {
      await refreshSummary();
    }
  }

  function bindClick(id, fn) {
    $(id).addEventListener("click", async () => {
      try {
        await fn();
      } catch (err) {
        log("ERR", err.message || String(err));
      }
    });
  }

  function bindListTabs() {
    const tabs = $("listTabs").querySelectorAll(".tab");
    for (const tab of tabs) {
      tab.addEventListener("click", async () => {
        try {
          state.listTab = tab.dataset.tab;
          updateTabButtons();
          await loadMeetings(state.listTab);
        } catch (err) {
          log("ERR", err.message || String(err));
        }
      });
    }
  }

  function bindSummaryTabs() {
    const tabs = $("summaryTabs").querySelectorAll(".section-tab");
    for (const tab of tabs) {
      tab.addEventListener("click", () => {
        state.summarySection = tab.dataset.section;
        renderSummaryTabs();
        renderSummaryBody();
      });
    }
  }

  async function handlePauseResumeButton() {
    await pauseOrResumeMeeting();
  }

  function initEvents() {
    bindClick("btnEnter", enterListFromEntry);
    bindClick("btnStartMeeting", startMeetingFromList);

    bindClick("btnRecordPauseResume", handlePauseResumeButton);
    bindClick("btnRecordFinish", finishMeetingFromRecord);

    bindClick("btnSummaryRefresh", refreshSummary);
    bindClick("btnSummaryPlayback", playAudio);
    bindClick("btnSummaryEdit", async () => {
      setEditMode(!state.editMode);
    });
    bindClick("btnSummaryKnowledge", async () => {
      openKnowledgeDrawer();
    });
    bindClick("btnSummaryShare", async () => {
      openShareSheet();
    });

    bindClick("btnEditSave", saveSummaryEdit);
    bindClick("btnEditCancel", async () => setEditMode(false));

    bindClick("btnCloseKnowledge", async () => closeKnowledgeDrawer());
    bindClick("btnShareClose", async () => closeShareSheet());

    bindClick("btnShareCreate", createShareLink);
    bindClick("btnShareCopy", copyShareLink);
    bindClick("btnShareVerify", verifyShare);
    bindClick("btnShareGet", getShareContent);
    bindClick("btnShareRevoke", revokeShare);

    bindClick("btnHealth", healthCheck);
    bindClick("btnReloadList", async () => loadMeetings(state.listTab));
    bindClick("btnRunWorker", triggerWorkerRunOnce);

    bindClick("btnNavBack", handleNavBack);
    bindClick("btnNavRefresh", refreshCurrentView);

    $("overlay").addEventListener("click", () => {
      closeKnowledgeDrawer();
      closeShareSheet();
    });

    window.addEventListener("beforeunload", () => {
      stopStreaming().catch(() => {});
      clearProcessingWatcher();
    });

    bindListTabs();
    bindSummaryTabs();
  }

  function initDefaults() {
    $("cfgBaseUrl").value = window.location.origin;
    setNavByView();
    renderRecordTimer();
    updateTabButtons();
    renderSummaryTabs();
    renderSummaryBody();
    setDebugKv();
    log("OK", "原型流程模拟器初始化完成");
  }

  function init() {
    initDefaults();
    initEvents();
  }

  init();
})();
