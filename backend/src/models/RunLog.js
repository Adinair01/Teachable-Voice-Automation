import mongoose from 'mongoose';

// kind: 'learn' | 'replay' | 'match' — drives the three metrics on the dashboard.
const runLogSchema = new mongoose.Schema({
  kind: { type: String, enum: ['learn', 'replay', 'match'], required: true, index: true },
  flowId: { type: mongoose.Schema.Types.ObjectId, ref: 'Flow', index: true },
  success: { type: Boolean, required: true },
  stepResults: [{ index: Number, ok: Boolean, usedLlmFallback: Boolean, error: String, _id: false }],
  utterance: String,
  createdAt: { type: Date, default: Date.now, index: true },
});

export const RunLog = mongoose.model('RunLog', runLogSchema);
