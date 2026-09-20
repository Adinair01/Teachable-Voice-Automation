import { Router } from 'express';
import mongoose from 'mongoose';
import { z } from 'zod';
import { Flow } from '../models/Flow.js';

export const flowsRouter = Router();

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

flowsRouter.delete('/:id', async (req, res, next) => {
  try {
    if (!mongoose.isValidObjectId(req.params.id)) return res.status(400).json({ error: 'invalid id' });
    const del = await Flow.findByIdAndDelete(req.params.id);
    if (!del) return res.status(404).json({ error: 'not found' });
    res.status(204).end();
  } catch (e) { next(e); }
});
