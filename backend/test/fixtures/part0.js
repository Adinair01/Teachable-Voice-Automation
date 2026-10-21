import { generalise, IMPLIED_SLOTS } from '../../src/services/generalise.js';
import { normaliseSlots, replaySlots } from '../../src/prompts/slots.js';
import { calculatorDemo, margheritaDemo, farmhouseDemo, calculatorReplays, toFarmhouse, toMargherita } from './demos.js';

export const FIXTURE_PATH = new URL('../../../android/app/src/test/resources/part0/flows.json', import.meta.url);

/** The flow as the session saves it (generalise minus its learn-time verdicts). */
export const learn = (demo) => { const { slotsFound, missing, ...flow } = generalise(demo); return flow; };

/** The values that reach the phone for a command, through the same functions the session uses. */
export const replayValues = (flow, model) => replaySlots(normaliseSlots(model), flow.slots, IMPLIED_SLOTS.map((s) => s.name)).slots;

export function part0Fixture() {
  const calc = learn(calculatorDemo);
  const marg = learn(margheritaDemo);
  const farm = learn(farmhouseDemo);
  return {
    calculator: { flow: calc, replays: calculatorReplays.map((r) => ({ said: r.said, slots: replayValues(calc, r.model), keys: r.keys })) },
    margheritaTaught: { flow: marg, replay: { said: toFarmhouse.said, slots: replayValues(marg, toFarmhouse.model) } },
    farmhouseTaught: { flow: farm, replay: { said: toMargherita.said, slots: replayValues(farm, toMargherita.model) } },
  };
}
