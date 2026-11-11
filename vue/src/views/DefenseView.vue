<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import Icon from '../components/Icon.vue'
import StatusPill from '../components/StatusPill.vue'
import EmptyState from '../components/EmptyState.vue'
import ProgressRing from '../components/ProgressRing.vue'
import { api, DIFFICULTY_LABEL } from '../api/endpoints'
import { currentRole } from '../stores/session'
import { confirm } from '../stores/confirm'
import { toast, toastError } from '../stores/toast'

const route = useRoute()
const sessionId = computed(() => String(route.params.sessionId || ''))
const isTeacher = computed(() => currentRole.value === 'TEACHER')
const isStudent = computed(() => currentRole.value === 'STUDENT')

const loading = ref(true)
const loadError = ref('')
const session = ref(null)
const busy = ref('')

const TYPE_LABEL = {
  DESIGN: '设计',
  CODE: '代码',
  REPORT: '报告',
  CONCEPT: '概念',
  EXTENSION: '延伸',
}
const MODE_LABEL = { PER_STUDENT: '按学生出题', PER_TOPIC: '按题目出题' }

const questions = computed(() => {
  const list = session.value?.questions
  if (!Array.isArray(list)) return []
  return [...list].sort((a, b) => (a.orderNo || 0) - (b.orderNo || 0))
})
const answeredCount = computed(() => {
  if (session.value?.answeredCount != null) return session.value.answeredCount
  return questions.value.filter((q) => q.answerStatus === 'TRANSCRIBED').length
})
const canStart = computed(() => session.value?.status === 'READY')
const canFinish = computed(() => session.value?.status === 'IN_PROGRESS')
const canAnswer = computed(() => session.value?.status === 'IN_PROGRESS')
const canEvaluate = computed(() => isTeacher.value && ['COMPLETED', 'EVALUATED'].includes(session.value?.status))
const modeText = computed(() => MODE_LABEL[session.value?.mode] || session.value?.mode || '—')
const statusHint = computed(() => {
  const status = session.value?.status
  if (status === 'CREATED' || status === 'GENERATING') {
    return { tone: 'alert--info', text: 'AI 正在生成答辩题目，稍后刷新本页即可开始。' }
  }
  if (status === 'READY') {
    return {
      tone: 'alert--info',
      text: isTeacher.value
        ? '题目已就绪。点击右上角「开始答辩」，学生端进入录音作答流程；结束答辩后可发起 AI 评分。'
        : '点击右上角「开始答辩」后按题目顺序录音作答，录音会自动上传并转写；全部完成后点击「结束答辩」等待教师评分。',
    }
  }
  if (status === 'COMPLETED') {
    return {
      tone: 'alert--info',
      text: isTeacher.value ? '学生已结束答辩，可点击「AI 评分」生成答辩成绩。' : '答辩已结束，等待教师完成评分。',
    }
  }
  if (status === 'EXPIRED') {
    return { tone: 'alert--warn', text: '本次答辩已过期，请联系教师重新发起。' }
  }
  return null
})

/* ---------------- 摄像头在场检测 ---------------- */
const CAPTURE_INTERVAL_MS = 3000
const CAPTURE_WIDTH = 320
const videoRef = ref(null)
const cameraState = ref('idle') // idle | starting | live | blocked | unsupported | closed
const cameraMessage = ref('')
const presence = ref('unknown') // unknown | present | absent | unavailable
const faceMissCount = ref(0)
const faceUnavailable = ref(false)
const faceCheckedAt = ref('')
const faceBusy = ref(false)
let cameraStream = null
let faceTimer = null
let cameraRequestSeq = 0
let faceInitialized = false

const canDetect = computed(() => ['READY', 'IN_PROGRESS'].includes(session.value?.status))

const cameraHint = computed(() => {
  if (cameraState.value === 'starting') return '摄像头启动中…'
  if (cameraState.value === 'blocked') return cameraMessage.value || '摄像头不可用'
  if (cameraState.value === 'unsupported') return '当前浏览器不支持摄像头'
  if (cameraState.value === 'closed') return '摄像头已关闭'
  if (!canDetect.value) return '答辩进行时才需要在场检测'
  return '摄像头未开启'
})

const presenceBadge = computed(() => {
  if (cameraState.value === 'starting') return { text: '正在开启摄像头…', tone: 'pill--info' }
  if (cameraState.value === 'live') {
    if (presence.value === 'present') return { text: '已检测到考生', tone: 'pill--ok' }
    if (presence.value === 'absent') return { text: `未检测到人脸（第 ${faceMissCount.value} 次）`, tone: 'pill--danger' }
    if (presence.value === 'unavailable') return { text: '在场检测不可用', tone: 'pill--warn' }
    return { text: '正在检测…', tone: 'pill--info' }
  }
  if (cameraState.value === 'blocked') return { text: '摄像头不可用', tone: 'pill--warn' }
  if (cameraState.value === 'unsupported') return { text: '当前浏览器不支持摄像头', tone: 'pill--info' }
  const present = session.value?.facePresent
  if (present != null || Number(session.value?.faceMissCount) > 0) {
    const miss = Number(session.value?.faceMissCount) || 0
    return {
      text: `${present ? '最近检测：有人' : '最近检测：无人'}${miss ? ` · 累计未检测 ${miss} 次` : ''}`,
      tone: present ? 'pill--ok' : 'pill--warn',
    }
  }
  return { text: isTeacher.value ? '暂无检测记录' : canDetect.value ? '摄像头未开启' : '尚无在场检测记录', tone: '' }
})

function stopFaceTimer() {
  if (faceTimer) {
    clearInterval(faceTimer)
    faceTimer = null
  }
}

function releaseCamera() {
  if (cameraStream) {
    cameraStream.getTracks().forEach((track) => track.stop())
    cameraStream = null
  }
  if (videoRef.value) videoRef.value.srcObject = null
}

function stopCamera() {
  cameraRequestSeq += 1
  stopFaceTimer()
  releaseCamera()
  if (cameraState.value === 'live' || cameraState.value === 'starting') cameraState.value = 'closed'
}

/** 抓当前视频帧，压缩成 JPEG(0.7) 的 base64（不带 data: 前缀）用于在场检测。 */
function captureFrameBase64() {
  const video = videoRef.value
  if (!video || !video.videoWidth) return ''
  const width = Math.min(CAPTURE_WIDTH, video.videoWidth)
  const height = Math.max(1, Math.round((video.videoHeight / video.videoWidth) * width))
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) return ''
  ctx.drawImage(video, 0, 0, width, height)
  const dataUrl = canvas.toDataURL('image/jpeg', 0.7)
  return dataUrl.split(',')[1] || ''
}

async function checkFace() {
  if (!canDetect.value || cameraState.value !== 'live' || faceBusy.value) return
  if (typeof document !== 'undefined' && document.hidden) return
  const imageBase64 = captureFrameBase64()
  if (!imageBase64) return
  faceBusy.value = true
  try {
    const result = await api.faceCheck(sessionId.value, imageBase64)
    faceCheckedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    faceUnavailable.value = false
    if (result?.present) {
      presence.value = 'present'
    } else {
      presence.value = 'absent'
      faceMissCount.value += 1
    }
  } catch (err) {
    // 在场检测失败不阻塞答辩：503 / 50301 表示 AI 人脸服务未配置，降级展示。
    if (err?.status === 503 || err?.code === 50301) {
      faceUnavailable.value = true
      presence.value = 'unavailable'
      stopFaceTimer()
    }
  } finally {
    faceBusy.value = false
  }
}

function startFaceTimer() {
  stopFaceTimer()
  faceTimer = setInterval(checkFace, CAPTURE_INTERVAL_MS)
  checkFace()
}

async function startCamera() {
  if (cameraState.value === 'live' || cameraState.value === 'starting') return
  if (!canDetect.value) return
  if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
    cameraState.value = 'unsupported'
    presence.value = 'unavailable'
    return
  }
  const requestSeq = ++cameraRequestSeq
  cameraState.value = 'starting'
  cameraMessage.value = ''
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: { width: { ideal: 640 }, height: { ideal: 480 } },
      audio: false,
    })
    if (requestSeq !== cameraRequestSeq) {
      stream.getTracks().forEach((track) => track.stop())
      return
    }
    cameraStream = stream
    const video = videoRef.value
    if (!video) {
      releaseCamera()
      cameraState.value = 'idle'
      return
    }
    video.srcObject = stream
    await video.play().catch(() => {})
    cameraState.value = 'live'
    presence.value = 'unknown'
    startFaceTimer()
  } catch (err) {
    cameraState.value = 'blocked'
    presence.value = 'unavailable'
    cameraMessage.value =
      err?.name === 'NotAllowedError'
        ? '摄像头未授权，可在浏览器地址栏允许后重试'
        : '摄像头不可用，不影响录音作答'
  }
}

function retryFaceCheck() {
  if (faceUnavailable.value) {
    faceUnavailable.value = false
    presence.value = 'unknown'
  }
  if (cameraState.value === 'live') {
    startFaceTimer()
  } else {
    startCamera()
  }
}

/* ---------------- 录音作答（16k mono WAV） ---------------- */
const TARGET_SAMPLE_RATE = 16000
const recordingQuestionId = ref('')
const recordElapsedMs = ref(0)
const uploadQuestionId = ref('')
const uploadProgress = ref(0)
const pendingUpload = ref(null) // { questionId, file, durationMs }
const recording = computed(() => Boolean(recordingQuestionId.value))
const activeQuestion = computed(() => questions.value.find((q) => q.id === recordingQuestionId.value) || null)
const recordLimit = computed(() => Number(activeQuestion.value?.maxAnswerSeconds) || 0)
const recordElapsedText = computed(() => {
  const seconds = recordElapsedMs.value / 1000
  const minutes = Math.floor(seconds / 60)
  const rest = seconds - minutes * 60
  return minutes > 0 ? `${minutes}:${rest.toFixed(1).padStart(4, '0')}` : `${rest.toFixed(1)}s`
})
const recordRemain = computed(() => {
  if (!recordLimit.value) return null
  return Math.max(0, Math.ceil(recordLimit.value - recordElapsedMs.value / 1000))
})
const recordPercent = computed(() => {
  if (!recordLimit.value) return 0
  return Math.min(100, Math.round((recordElapsedMs.value / (recordLimit.value * 1000)) * 100))
})

let audioContext = null
let audioStream = null
let audioSource = null
let audioProcessor = null
let audioSink = null
let recordChunks = []
let recordTimer = null
let recordStartedAt = 0

function releaseAudio() {
  if (recordTimer) {
    clearInterval(recordTimer)
    recordTimer = null
  }
  if (audioProcessor) {
    audioProcessor.onaudioprocess = null
    try {
      audioProcessor.disconnect()
    } catch {
      // 节点可能已断开
    }
    audioProcessor = null
  }
  if (audioSource) {
    try {
      audioSource.disconnect()
    } catch {
      // 节点可能已断开
    }
    audioSource = null
  }
  if (audioSink) {
    try {
      audioSink.disconnect()
    } catch {
      // 节点可能已断开
    }
    audioSink = null
  }
  if (audioStream) {
    audioStream.getTracks().forEach((track) => track.stop())
    audioStream = null
  }
  if (audioContext) {
    audioContext.close().catch(() => {})
    audioContext = null
  }
}

function mergeChunks(chunks) {
  let total = 0
  for (const chunk of chunks) total += chunk.length
  const merged = new Float32Array(total)
  let offset = 0
  for (const chunk of chunks) {
    merged.set(chunk, offset)
    offset += chunk.length
  }
  return merged
}

/** 线性插值降采样到 16k，保证后端无需 ffmpeg。 */
function downsample(samples, inputRate) {
  if (!samples.length) return new Float32Array(0)
  if (inputRate === TARGET_SAMPLE_RATE) return samples
  const ratio = inputRate / TARGET_SAMPLE_RATE
  const length = Math.max(1, Math.floor(samples.length / ratio))
  const output = new Float32Array(length)
  for (let i = 0; i < length; i += 1) {
    const position = i * ratio
    const index = Math.floor(position)
    const fraction = position - index
    const current = samples[index] || 0
    const next = index + 1 < samples.length ? samples[index + 1] : current
    output[i] = current + (next - current) * fraction
  }
  return output
}

/** 拼 44 字节标准 WAV 头 + 16-bit PCM mono 数据。 */
function encodeWav(chunks, inputRate) {
  const merged = mergeChunks(chunks)
  const pcm = downsample(merged, inputRate)
  const buffer = new ArrayBuffer(44 + pcm.length * 2)
  const view = new DataView(buffer)
  const writeString = (offset, text) => {
    for (let i = 0; i < text.length; i += 1) view.setUint8(offset + i, text.charCodeAt(i))
  }
  const dataLength = pcm.length * 2
  writeString(0, 'RIFF')
  view.setUint32(4, 36 + dataLength, true)
  writeString(8, 'WAVE')
  writeString(12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true) // PCM
  view.setUint16(22, 1, true) // mono
  view.setUint32(24, TARGET_SAMPLE_RATE, true)
  view.setUint32(28, TARGET_SAMPLE_RATE * 2, true) // byte rate
  view.setUint16(32, 2, true) // block align
  view.setUint16(34, 16, true) // bits per sample
  writeString(36, 'data')
  view.setUint32(40, dataLength, true)
  let offset = 44
  for (let i = 0; i < pcm.length; i += 1) {
    const sample = Math.max(-1, Math.min(1, pcm[i]))
    view.setInt16(offset, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true)
    offset += 2
  }
  return new Blob([buffer], { type: 'audio/wav' })
}

function makeWavFile(blob, name) {
  try {
    return new File([blob], name, { type: 'audio/wav' })
  } catch {
    return blob
  }
}

function tickRecording() {
  if (!recordingQuestionId.value) return
  recordElapsedMs.value = Date.now() - recordStartedAt
  if (recordLimit.value && recordElapsedMs.value >= recordLimit.value * 1000) {
    void stopRecording({ upload: true })
  }
}

async function startRecording(question) {
  if (!canAnswer.value || recording.value || uploadQuestionId.value) return
  if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
    toast.error('无法录音', '当前浏览器不支持麦克风采集')
    return
  }
  const AudioContextClass = window.AudioContext || window.webkitAudioContext
  if (!AudioContextClass) {
    toast.error('无法录音', '当前浏览器不支持 AudioContext')
    return
  }
  let stream
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
    })
  } catch (err) {
    toast.error('无法开始录音', err?.name === 'NotAllowedError' ? '请在浏览器中允许使用麦克风' : '麦克风不可用')
    return
  }
  try {
    audioStream = stream
    audioContext = new AudioContextClass()
    if (audioContext.state === 'suspended') await audioContext.resume().catch(() => {})
    audioSource = audioContext.createMediaStreamSource(stream)
    audioProcessor = audioContext.createScriptProcessor(4096, 1, 1)
    recordChunks = []
    audioProcessor.onaudioprocess = (event) => {
      if (!recordingQuestionId.value) return
      recordChunks.push(new Float32Array(event.inputBuffer.getChannelData(0)))
    }
    // 用 0 增益节点把 processor 接到 destination，既让节点持续回调又不会把麦克风外放。
    audioSink = audioContext.createGain()
    audioSink.gain.value = 0
    audioSource.connect(audioProcessor)
    audioProcessor.connect(audioSink)
    audioSink.connect(audioContext.destination)
    if (pendingUpload.value?.questionId === question.id) pendingUpload.value = null
    recordStartedAt = Date.now()
    recordElapsedMs.value = 0
    recordingQuestionId.value = question.id
    recordTimer = setInterval(tickRecording, 100)
  } catch (err) {
    releaseAudio()
    recordingQuestionId.value = ''
    toast.error('无法开始录音', '麦克风初始化失败，请重试或更换浏览器')
  }
}

async function stopRecording({ upload = true } = {}) {
  const questionId = recordingQuestionId.value
  if (!questionId) return
  const elapsedMs = Math.max(0, Math.round(Date.now() - recordStartedAt))
  const chunks = recordChunks.slice()
  const inputRate = audioContext?.sampleRate || 48000
  recordingQuestionId.value = ''
  recordElapsedMs.value = 0
  releaseAudio()
  if (!upload || !chunks.length) return
  const blob = encodeWav(chunks, inputRate)
  const file = makeWavFile(blob, `defense-${sessionId.value}-q${questionId}-${Date.now()}.wav`)
  await uploadAnswer(questionId, file, elapsedMs)
}

function applyQuestionUpdate(updated) {
  if (!updated?.id || !Array.isArray(session.value?.questions)) return
  const index = session.value.questions.findIndex((q) => q.id === updated.id)
  if (index >= 0) session.value.questions[index] = { ...session.value.questions[index], ...updated }
}

async function uploadAnswer(questionId, file, durationMs) {
  uploadQuestionId.value = questionId
  uploadProgress.value = 0
  try {
    const updated = await api.uploadDefenseAnswer(sessionId.value, questionId, file, durationMs, {
      onProgress: (percent) => {
        uploadProgress.value = percent
      },
    })
    pendingUpload.value = null
    applyQuestionUpdate(updated)
    const transcribed = updated?.answerStatus === 'TRANSCRIBED' || Boolean(updated?.transcript)
    toast.ok('作答已上传', transcribed ? '语音已转写为文字，可继续下一题' : '语音转写处理中，可稍后刷新查看')
    await load(true)
  } catch (err) {
    pendingUpload.value = { questionId, file, durationMs }
    if (err?.status === 503 || err?.code === 50301) {
      toast.error('语音转写暂不可用', '录音已保留在页面内，可稍后点「重试上传」；也可继续下一题')
    } else {
      toastError(err, '上传作答失败')
    }
    // 失败时后端会把作答标成 FAILED，刷新一次让界面与状态机一致。
    await load(true)
  } finally {
    uploadQuestionId.value = ''
    uploadProgress.value = 0
  }
}

function retryPendingUpload() {
  const pending = pendingUpload.value
  if (!pending) return
  pendingUpload.value = null
  void uploadAnswer(pending.questionId, pending.file, pending.durationMs)
}

/* ---------------- 会话数据 / 状态操作 ---------------- */
async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const data = await api.defenseSession(sessionId.value)
    session.value = data
    loadError.value = ''
    if (!faceInitialized) {
      faceMissCount.value = Number(data?.faceMissCount) || 0
      faceInitialized = true
    }
  } catch (err) {
    if (!silent) {
      loadError.value = err?.message || '加载答辩会话失败'
      toastError(err, '加载答辩会话失败')
    }
  } finally {
    if (!silent) loading.value = false
  }
}

async function doStart() {
  busy.value = 'start'
  try {
    await api.startDefense(sessionId.value)
    toast.ok('答辩已开始', '请按题目顺序录音作答')
    await load(true)
  } catch (err) {
    toastError(err, '开始答辩失败')
  } finally {
    busy.value = ''
  }
}

async function doFinish() {
  if (recording.value || uploadQuestionId.value) {
    toast.info('请先结束当前录音', '录音结束并上传完成后再结束答辩')
    return
  }  if (
    !(await confirm({
      title: '结束答辩',
      message: '结束后将不能继续录音作答，教师会基于已转写的回答评分。确定结束吗？',
      confirmText: '结束答辩',
    }))
  ) {
    return
  }
  busy.value = 'finish'
  try {
    await api.finishDefense(sessionId.value)
    toast.ok('答辩已结束', '等待教师完成评分')
    await load(true)
  } catch (err) {
    toastError(err, '结束答辩失败')
  } finally {
    busy.value = ''
  }
}

async function doEvaluate() {
  if (!canEvaluate.value) return
  const reevaluate = session.value?.status === 'EVALUATED'
  if (
    !(await confirm({
      title: reevaluate ? '重新评分' : 'AI 评分',
      message: '将把全部题目与转写作答提交 AI 评分；AI 不可用时会报错，不会生成假分数。',
      confirmText: reevaluate ? '重新评分' : '开始评分',
    }))
  ) {
    return
  }
  busy.value = 'evaluate'
  try {
    const result = await api.evaluateDefense(sessionId.value)
    toast.ok('评分完成', result?.grade ? `${result.score ?? '—'} 分 · ${result.grade}` : `答辩得分 ${result?.score ?? '—'}`)
    await load(true)
  } catch (err) {
    toastError(err, '评分失败')
  } finally {
    busy.value = ''
  }
}

/* ---------------- 展示辅助 ---------------- */
function fmtTime(value) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function fmtDuration(ms) {
  const total = Math.round((Number(ms) || 0) / 1000)
  if (total <= 0) return '—'
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return minutes > 0 ? `${minutes} 分 ${seconds} 秒` : `${seconds} 秒`
}

function answerText(status) {
  return { TRANSCRIBED: '已转写', TRANSCRIBING: '转写中', UPLOADED: '已上传', FAILED: '转写失败' }[status] || '未作答'
}

function answerTone(status) {
  if (status === 'TRANSCRIBED') return 'pill--ok'
  if (status === 'TRANSCRIBING' || status === 'UPLOADED') return 'pill--info'
  if (status === 'FAILED') return 'pill--danger'
  return ''
}

function sourceRefsText(value) {
  if (!value) return ''
  try {
    const parsed = typeof value === 'string' ? JSON.parse(value) : value
    if (Array.isArray(parsed)) {
      return parsed.map((item) => (typeof item === 'string' ? item : JSON.stringify(item))).join('、')
    }
    if (parsed && typeof parsed === 'object') return JSON.stringify(parsed)
  } catch {
    // 不是 JSON 就原样展示
  }
  return String(value)
}

/** 后端班级画像里 expectedPoints 是 List<String>（教师视角），统一转成可读文本。 */
function pointsText(value) {
  if (Array.isArray(value)) return value.filter(Boolean).join('\n')
  if (value == null) return ''
  return String(value)
}

function hasTeacherMeta(question) {
  return isTeacher.value && Boolean(pointsText(question.expectedPoints) || pointsText(question.referenceAnswer))
}

watch(
  () => session.value?.status,
  async (status) => {
    if (!status) return
    if (['COMPLETED', 'EVALUATED', 'EXPIRED'].includes(status)) {
      if (recordingQuestionId.value) {
        recordingQuestionId.value = ''
        recordElapsedMs.value = 0
        releaseAudio()
      }
      stopCamera()
      return
    }
    if (isStudent.value && ['READY', 'IN_PROGRESS'].includes(status) && cameraState.value !== 'live') {
      await nextTick()
      if (canDetect.value && cameraState.value !== 'live') void startCamera()
    }
  },
)

// 同一路由切换 sessionId 时复用组件：重置检测状态并重新加载。
watch(sessionId, async () => {
  stopCamera()
  cameraState.value = 'idle'
  presence.value = 'unknown'
  faceCheckedAt.value = ''
  faceMissCount.value = 0
  faceInitialized = false
  session.value = null
  await load()
  await nextTick()
  if (isStudent.value && canDetect.value) void startCamera()
})

onMounted(async () => {
  await load()
  await nextTick()
  if (isStudent.value && canDetect.value) void startCamera()
})

onBeforeUnmount(() => {
  stopCamera()
  if (recordingQuestionId.value) {
    recordingQuestionId.value = ''
    recordElapsedMs.value = 0
  }
  releaseAudio()
})
</script>

<template>
  <div>
    <div class="page-head">
      <div style="min-width: 0">
        <div class="row small dim" style="margin-bottom: 6px">
          <RouterLink :to="isTeacher ? '/offerings' : '/selections'">{{ isTeacher ? '教学班' : '我的选题' }}</RouterLink>
          <Icon name="chevron-right" :size="12" />
          <span class="truncate">答辩</span>
        </div>
        <h1 class="row row--wrap" style="gap: 10px">
          <span class="truncate">{{ session?.studentName || '答辩会话' }}</span>
          <StatusPill v-if="session" :status="session.status" />
        </h1>
        <p v-if="session">
          <span v-if="session.studentNo" class="mono">{{ session.studentNo }}</span>
          <span v-if="session.className"> · {{ session.className }}</span>
          · 作答 {{ answeredCount }} / {{ session.questionCount ?? questions.length }}
          <span v-if="session.durationMs"> · 答辩用时 {{ fmtDuration(session.durationMs) }}</span>
        </p>
        <p v-else>语音作答自动转写，教师统一评分。</p>
      </div>
      <div v-if="session" class="page-head__actions">
        <button class="btn btn--ghost" :disabled="loading || Boolean(busy)" @click="load(true)">
          <Icon name="refresh" :size="15" />刷新
        </button>
        <RouterLink
          v-if="isTeacher"
          :to="`/offerings/${session.offeringId}`"
          class="btn btn--ghost btn--sm"
        >
          <Icon name="book" :size="14" />教学班
        </RouterLink>
        <button v-if="canStart" class="btn btn--primary" :disabled="Boolean(busy)" @click="doStart">
          <span v-if="busy === 'start'" class="spinner" /><Icon v-else name="play" :size="15" />开始答辩
        </button>
        <button v-if="canFinish" class="btn btn--danger" :disabled="Boolean(busy) || recording || Boolean(uploadQuestionId)" @click="doFinish">
          <span v-if="busy === 'finish'" class="spinner" /><Icon v-else name="check" :size="15" />结束答辩
        </button>
        <button v-if="canEvaluate" class="btn btn--primary" :disabled="Boolean(busy)" @click="doEvaluate">
          <span v-if="busy === 'evaluate'" class="spinner" /><Icon v-else name="spark" :size="15" />{{
            session.status === 'EVALUATED' ? '重新评分' : 'AI 评分'
          }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="grid grid--sidebar">
      <div class="skeleton" style="height: 320px" />
      <div class="skeleton" style="height: 320px" />
    </div>

    <EmptyState v-else-if="loadError" icon="alert" title="加载答辩会话失败" :hint="loadError">
      <RouterLink :to="isTeacher ? '/offerings' : '/selections'" class="btn btn--primary btn--sm" style="margin-top: 10px">
        返回列表
      </RouterLink>
    </EmptyState>

    <template v-else-if="session">
      <div v-if="statusHint" class="alert" :class="statusHint.tone" style="margin-bottom: 14px">
        <Icon name="flag" :size="15" style="flex: none; margin-top: 2px" />
        <span>{{ statusHint.text }}</span>
      </div>

      <div v-if="session.score != null" class="card card--pad" style="margin-bottom: 16px">
        <div class="row row--wrap" style="align-items: center; gap: 18px">
          <ProgressRing :value="Number(session.score) || 0" :size="72" label="答辩" tone="ok" />
          <div style="min-width: 0; flex: 1">
            <div class="row row--wrap" style="gap: 8px">
              <span class="section-title" style="margin: 0"><Icon name="chart" :size="14" />答辩评分结果</span>
              <span v-if="session.grade" class="pill pill--ok">{{ session.grade }}</span>
              <span class="small dim">评分时间 {{ fmtTime(session.evaluatedAt) }}</span>
            </div>
            <p class="small" style="margin-top: 8px; white-space: pre-wrap">{{ session.summary || 'AI 未返回评语。' }}</p>
          </div>
        </div>
      </div>

      <div class="grid grid--sidebar">
        <div>
          <div class="row" style="margin-bottom: 10px">
            <div class="section-title" style="margin: 0"><Icon name="file" :size="14" />答辩题目（{{ questions.length }}）</div>
            <span class="spacer" />
            <span class="small dim">
              {{ isStudent ? '按顺序录音作答，录音自动上传并转写' : '教师视角：可查看评分要点与参考答案' }}
            </span>
          </div>

          <EmptyState
            v-if="!questions.length"
            icon="file"
            title="还没有答辩题目"
            hint="题目由 AI 按选题与材料生成，可稍后刷新本页"
          />

          <article v-for="q in questions" :key="q.id" class="card" style="margin-bottom: 12px">
            <div class="card__body" style="display: grid; gap: 12px">
              <div class="row row--wrap" style="align-items: flex-start">
                <span class="tag">第 {{ q.orderNo }} 题</span>
                <span class="tag">{{ TYPE_LABEL[q.type] || q.type || '综合' }}</span>
                <span class="tag">{{ DIFFICULTY_LABEL[q.difficulty] || q.difficulty || '不限难度' }}</span>
                <span class="pill pill--info">
                  <Icon name="clock" :size="12" />{{ q.maxAnswerSeconds ? `限时 ${q.maxAnswerSeconds}s` : '不限时' }}
                </span>
                <span class="spacer" />
                <span class="pill" :class="answerTone(q.answerStatus)">{{ answerText(q.answerStatus) }}</span>
              </div>

              <div class="q-text">{{ q.question }}</div>

              <div v-if="hasTeacherMeta(q)" class="grid grid--2">
                <div v-if="pointsText(q.expectedPoints)" class="alert alert--info" style="align-items: flex-start">
                  <Icon name="target" :size="15" style="flex: none; margin-top: 2px" />
                  <div style="min-width: 0">
                    <strong>评分要点</strong>
                    <div class="small transcript" style="margin-top: 4px">{{ pointsText(q.expectedPoints) }}</div>
                  </div>
                </div>
                <div v-if="pointsText(q.referenceAnswer)" class="alert alert--ok" style="align-items: flex-start">
                  <Icon name="check" :size="15" style="flex: none; margin-top: 2px" />
                  <div style="min-width: 0">
                    <strong>参考答案</strong>
                    <div class="small transcript" style="margin-top: 4px">{{ pointsText(q.referenceAnswer) }}</div>
                  </div>
                </div>
              </div>

              <div v-if="sourceRefsText(q.sourceRefs)" class="small dim">来源：{{ sourceRefsText(q.sourceRefs) }}</div>

              <div v-if="q.transcript" class="alert alert--ok" style="align-items: flex-start">
                <Icon name="file" :size="15" style="flex: none; margin-top: 2px" />
                <div style="min-width: 0">
                  <strong>语音转写</strong>
                  <div class="small transcript" style="margin-top: 4px">{{ q.transcript }}</div>
                </div>
              </div>

              <div class="row row--wrap" style="align-items: center">
                <template v-if="recordingQuestionId === q.id">
                  <span class="pill pill--danger"><span class="rec-dot" />录音中 {{ recordElapsedText }}</span>
                  <span
                    v-if="recordRemain !== null"
                    class="pill"
                    :class="recordRemain <= 10 ? 'pill--danger' : 'pill--info'"
                  >
                    剩余 {{ recordRemain }}s
                  </span>
                  <button class="btn btn--danger btn--sm" @click="stopRecording({ upload: true })">
                    <Icon name="check" :size="14" />结束并上传
                  </button>
                  <button class="btn btn--icon btn--sm" title="取消本次录音" @click="stopRecording({ upload: false })">
                    <Icon name="trash" :size="14" />
                  </button>
                </template>
                <template v-else-if="uploadQuestionId === q.id">
                  <div style="flex: 1; min-width: 150px; max-width: 280px">
                    <div class="bar"><div class="bar__fill" :style="{ width: uploadProgress + '%' }" /></div>
                  </div>
                  <span class="small dim num">上传中 {{ uploadProgress }}%</span>
                </template>
                <template v-else>
                  <button
                    v-if="canAnswer"
                    class="btn btn--primary btn--sm"
                    :disabled="recording || Boolean(uploadQuestionId)"
                    @click="startRecording(q)"
                  >
                    <Icon name="play" :size="14" />{{ q.transcript ? '重新录音' : '录音作答' }}
                  </button>
                  <button
                    v-if="pendingUpload && pendingUpload.questionId === q.id"
                    class="btn btn--ghost btn--sm"
                    :disabled="Boolean(uploadQuestionId)"
                    @click="retryPendingUpload"
                  >
                    <Icon name="refresh" :size="14" />重试上传
                  </button>
                  <button
                    v-if="pendingUpload && pendingUpload.questionId === q.id"
                    class="btn btn--icon btn--sm"
                    title="丢弃这段录音"
                    @click="pendingUpload = null"
                  >
                    <Icon name="trash" :size="14" />
                  </button>
                  <span v-if="q.answerDurationMs" class="small dim">作答时长 {{ (q.answerDurationMs / 1000).toFixed(1) }}s</span>
                  <span v-if="!canAnswer" class="small dim">
                    {{ session.status === 'READY' ? '开始答辩后可录音作答' : '答辩已结束' }}
                  </span>
                </template>
                <span class="spacer" />
              </div>

              <div v-if="recordingQuestionId === q.id && recordLimit" class="bar">
                <div
                  class="bar__fill"
                  :style="{ width: recordPercent + '%', background: recordRemain <= 10 ? 'var(--danger)' : '' }"
                />
              </div>
            </div>
          </article>
        </div>

        <aside style="display: grid; gap: 16px; align-content: start">
          <div class="card">
            <div class="card__head">
              <div>
                <h3><Icon name="shield" :size="15" style="margin-right: 6px; vertical-align: -2px" />在场检测</h3>
                <div class="small dim">只判断画面中是否有人，用于记录在场情况</div>
              </div>
              <span class="pill" :class="presenceBadge.tone" style="margin-left: auto">{{ presenceBadge.text }}</span>
            </div>
            <div class="card__body" style="display: grid; gap: 10px">
              <template v-if="isStudent">
                <div class="cam-frame">
                  <video ref="videoRef" autoplay muted playsinline />
                  <div v-if="cameraState !== 'live'" class="cam-frame__placeholder">
                    <Icon name="eye" :size="26" />
                    <span class="small">{{ cameraHint }}</span>
                  </div>
                </div>

                <div class="row row--wrap">
                  <button
                    v-if="cameraState !== 'live'"
                    class="btn btn--primary btn--sm"
                    :disabled="!canDetect || cameraState === 'starting' || cameraState === 'unsupported'"
                    @click="startCamera"
                  >
                    <Icon name="eye" :size="14" />开启摄像头
                  </button>
                  <button v-else class="btn btn--ghost btn--sm" @click="stopCamera">
                    <Icon name="x" :size="14" />关闭摄像头
                  </button>
                  <button
                    v-if="faceUnavailable && canDetect"
                    class="btn btn--ghost btn--sm"
                    @click="retryFaceCheck"
                  >
                    <Icon name="refresh" :size="14" />重试检测
                  </button>
                  <span class="spacer" />
                  <span v-if="faceCheckedAt" class="small dim">最近检测 {{ faceCheckedAt }}</span>
                </div>

                <div class="alert alert--info" style="align-items: flex-start">
                  <Icon name="lock" :size="15" style="flex: none; margin-top: 2px" />
                  <span>隐私提示：摄像头仅用于在场检测，画面不留存，也不做身份识别。</span>
                </div>
                <div v-if="faceUnavailable" class="alert alert--warn" style="align-items: flex-start">
                  <Icon name="alert" :size="15" style="flex: none; margin-top: 2px" />
                  <span>在场检测暂不可用（后端未配置人脸服务），不影响录音作答与评分。</span>
                </div>
              </template>
              <template v-else>
                <div class="alert alert--info" style="align-items: flex-start">
                  <Icon name="lock" :size="15" style="flex: none; margin-top: 2px" />
                  <span>
                    在场检测由学生在自己的设备上开启摄像头完成，画面不留存；教师端只查看服务端记录的检测结果，
                    避免用教师本机摄像头污染该学生的在场数据。
                  </span>
                </div>
                <dl class="dl">
                  <dt>最近结果</dt>
                  <dd>
                    <span v-if="session.facePresent != null">{{ session.facePresent ? '检测到人' : '未检测到人' }}</span>
                    <span v-else class="dim">暂无记录</span>
                  </dd>
                  <dt>未检测次数</dt>
                  <dd class="num">{{ session.faceMissCount ?? 0 }}</dd>
                </dl>
              </template>
            </div>
          </div>

          <div class="card">
            <div class="card__head">
              <div>
                <h3>答辩信息</h3>
                <div class="small dim">状态由服务端维护，刷新可获取最新进度</div>
              </div>
            </div>
            <div class="card__body">
              <dl class="dl">
                <dt>状态</dt>
                <dd><StatusPill :status="session.status" /></dd>
                <dt>出题方式</dt>
                <dd>{{ modeText }}</dd>
                <dt>作答进度</dt>
                <dd class="num">{{ answeredCount }} / {{ session.questionCount ?? questions.length }}</dd>
                <dt>开始时间</dt>
                <dd>{{ fmtTime(session.startedAt) }}</dd>
                <dt>结束时间</dt>
                <dd>{{ fmtTime(session.finishedAt) }}</dd>
                <dt>评分时间</dt>
                <dd>{{ fmtTime(session.evaluatedAt) }}</dd>
                <dt>答辩时长</dt>
                <dd>{{ fmtDuration(session.durationMs) }}</dd>
                <dt>在场检测</dt>
                <dd>
                  <span v-if="session.facePresent != null">{{ session.facePresent ? '最近检测到人' : '最近未检测到人' }}</span>
                  <span v-else class="dim">暂无记录</span>
                  <span v-if="session.faceMissCount" class="dim"> · 未检测 {{ session.faceMissCount }} 次</span>
                </dd>
              </dl>
            </div>
          </div>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.cam-frame {
  position: relative;
  overflow: hidden;
  aspect-ratio: 4 / 3;
  border-radius: var(--r);
  background: #0b1020;
}

.cam-frame video {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cam-frame__placeholder {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  gap: 8px;
  padding: 16px;
  color: #c7d2fe;
  text-align: center;
}

.q-text {
  font-size: 15px;
  font-weight: 650;
  line-height: 1.65;
}

.transcript {
  white-space: pre-wrap;
  word-break: break-word;
}

.rec-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 6px;
  border-radius: 50%;
  background: var(--danger);
  animation: rec-pulse 1.2s ease-in-out infinite;
}

@keyframes rec-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.25;
  }
}
</style>
