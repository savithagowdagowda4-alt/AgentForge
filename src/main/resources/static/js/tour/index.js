import { TourPlayer } from './player.js';
import { CHAPTERS } from './chapters.js';

let player = null;
export function tour() {
  if (!player) player = new TourPlayer(CHAPTERS);
  return player;
}
export function playTutorial(chapterId = null, continueAfter = true) {
  const i = chapterId ? Math.max(0, CHAPTERS.findIndex((c) => c.id === chapterId)) : 0;
  try { localStorage.setItem('af-tour-seen', '1'); } catch { /* ignore */ }
  tour().play(i, continueAfter);
}
export { CHAPTERS };
