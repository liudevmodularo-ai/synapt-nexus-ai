# 🧠 Synapt Nexus AI

> App Android nativo — hub de IA local com mesh neural distribuída.
> Roda LLMs quantizados on-device, serve clientes Web via SNW v1,
> e conecta múltiplos dispositivos em uma rede neural inteligente.

---

## 🚀 Setup Rápido

```bash
chmod +x setup.sh && ./setup.sh
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

**Requisitos:** Android Studio Hedgehog+, NDK r26+, JDK 17+, Git

---

## 📱 Hardware Alvo — Redmi Note 14 Pro

| Componente | Spec | Impacto |
|---|---|---|
| SoC | Dimensity 7300 | CPU-bound inference |
| CPU | 4x A78 @ 2.5GHz | Pinned via affinity |
| RAM | 12GB LPDDR4X | Tier MID→HIGH |
| Storage | 256/512GB UFS2.2 | ~15GB para modelos |
| GPU | Mali-G615 MC2 | Sem offload GPU (limitado) |

**DeviceScore estimado: 55–70/100 → Tier MID/HIGH**

---

## 🌐 Protocolo SNW v1 — Porta 7474

WebOS = qualquer browser moderno em HTTPS/WSS.

| Endpoint | Auth | Descrição |
|---|---|---|
| `POST /v1/pair` | ❌ | Handshake inicial |
| `POST /v1/pair/confirm` | ❌ | Emite Bearer token |
| `GET  /v1/status` | ✅ | Health + métricas |
| `GET  /v1/models` | ✅ | Modelos disponíveis |
| `POST /v1/inference/load` | ✅ | Carrega modelo |
| `POST /v1/generate` | ✅ | One-shot |
| `WS   /v1/stream` | ✅ | Streaming de tokens |

---

## 🧠 Dual Mesh Neural

```
OFFLINE  → WiFi Direct P2P (sem roteador)
ONLINE   → mDNS LAN + WireGuard VPN internet
HYBRID   → mix automático, melhor nó ganha
```

MeshInferenceRouter: RouteScore = deviceScore × (1-load) × thermalFactor − latencyPenalty

---

## 📦 Modelos Auto-Selecionados

| Tier | Modelo | Formato | RAM |
|---|---|---|---|
| LOW | Gemma 2 2B | GGUF Q4 | 1.6GB |
| MID | Mistral 7B | GGUF Q4 | 4.1GB |
| HIGH | Qwen 2.5 7B | GGUF Q4 | 4.4GB |

---

## 🗺️ Roadmap

- ✅ **Sprint 1** — Scaffold, LlamaCpp JNI, SNW Server, Security, Thermal, Agent UI
- 🔄 **Sprint 2** — DeviceProfiler, ModelSelector, OnnxBridge, Download UI
- ⏳ **Sprint 3** — WireGuard, NFC/QR Pairing, WiFi Direct, MeshScreen
- ⏳ **Sprint 4** — Polish, CI/CD, Play Store

---

## 👥 Squad

🤖 ARIA · ⚙️ VECTOR · 🌐 NEXUS · 🔐 CIPHER · 🚀 PULSE · 🎨 FLUX
