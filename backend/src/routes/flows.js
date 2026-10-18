import { Router } from 'express';
import mongoose from 'mongoose';
import { z } from 'zod';
import { Flow, STEP_TYPES } from '../models/Flow.js';

export const flowsRouter = Router();

const selector = z.object({
  resourceId: z.string().max(200).default(''), text: z.string().max(300).default(''),
  contentDesc: z.string().max(300).default(''), className: z.string().max(200).default(''),
  near: z.string().max(300).default(''),
});
const step = z.object({
  type: z.enum(STEP_TYPES),
  selector: selector.default({}),
  slotRef: z.string().max(40).nullable().default(null),
  value: z.string().max(500).default(''),
  sensitive: z.boolean().default(false),
  expect: z.object({ package: z.string().default(''), textContains: z.string().default('') }).default({}),
  optional: z.boolean().default(false),
});
export const flowInput = z.object({
  intentKey: z.string().min(1).max(80),
  description: z.string().min(1).max(300),
  appPackage: z.string().min(1).max(200),
  slots: z.array(z.object({ name: z.string().max(40), example: z.string().max(200) })).default([]),
  steps: z.array(step).min(1).max(100),
});

const listQuery = z.object({
  limit: z.coerce.number().int().min(1).max(100).default(50),
  skip: z.coerce.number().int().min(0).default(0),
});

flowsRouter.get('/', async (req, res, next) => {
  try {
    const { limit, skip } = listQuery.parse(req.query);
    const flows = await Flow.find().sort({ createdAt: -1 }).skip(skip).limit(limit).lean();
    res.json({ flows });
  } catch (e) { next(e); }
});

flowsRouter.post('/', async (req, res, next) => {
  try { res.status(201).json(await Flow.create(flowInput.parse(req.body))); } catch (e) { next(e); }
});

flowsRouter.get('/:id', async (req, res, next) => {
  try {
    if (!mongoose.isValidObjectId(req.params.id)) return res.status(400).json({ error: 'invalid id' });
    const flow = await Flow.findById(req.params.id).lean();
    return flow ? res.json(flow) : res.status(404).json({ error: 'not found' });
  } catch (e) { next(e); }
});

flowsRouter.delete('/:id', async (req, res, next) => {
  try {
    if (!mongoose.isValidObjectId(req.params.id)) return res.status(400).json({ error: 'invalid id' });
    const del = await Flow.findByIdAndDelete(req.params.id);
    if (!del) return res.status(404).json({ error: 'not found' });
    res.status(204).end();
  } catch (e) { next(e); }
});
