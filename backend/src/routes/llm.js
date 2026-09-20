import { Router } from 'express';
import { pickElement, pickElementInput } from '../prompts/pickElement.js';
import { extractIntent } from '../prompts/intent.js';
import { z } from 'zod';

export const llmRouter = Router();

llmRouter.post('/pick-element', async (req, res, next) => {
  try { res.json(await pickElement(pickElementInput.parse(req.body))); } catch (e) { next(e); }
});

llmRouter.post('/intent', async (req, res, next) => {
  try {
    const { utterance } = z.object({ utterance: z.string().min(1).max(500) }).parse(req.body);
    res.json(await extractIntent(utterance));
  } catch (e) { next(e); }
});
