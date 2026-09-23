import mongoose from 'mongoose';

// set_quantity / choose_address: added to ordering flows by generalise (see services/generalise.js).
export const STEP_TYPES = ['launch', 'tap', 'type', 'scroll', 'back', 'wait', 'set_quantity', 'choose_address'];

const selector = new mongoose.Schema(
  { resourceId: String, text: String, contentDesc: String, className: String, near: String },
  { _id: false },
);
const step = new mongoose.Schema(
  {
    type: { type: String, enum: STEP_TYPES, required: true },
    selector,
    slotRef: { type: String, default: null },
    value: String,
    sensitive: { type: Boolean, default: false },
    expect: { package: String, textContains: String },
  },
  { _id: false },
);

const flowSchema = new mongoose.Schema({
  intentKey: { type: String, required: true, index: true },
  description: { type: String, required: true },
  appPackage: { type: String, required: true, index: true },
  slots: [{ name: String, example: String, _id: false }],
  steps: [step],
  createdAt: { type: Date, default: Date.now, index: true },
});

export const Flow = mongoose.model('Flow', flowSchema);
