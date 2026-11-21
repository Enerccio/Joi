// The controller of the JavaFX node's screenshot mode (joi-node fx/NodeShots, started by bin/start-node.sh): every function is a
// command that the node carries out and answers. The commands are listed in fx/ShotCommands.java.
export {sleep, root, repo, outDir} from './shot.mjs';

const nodeUrl = `http://127.0.0.1:${process.env.SHOT_NODE_PORT || 8097}/do`;
export const serverUrl = process.env.SHOT_URL || 'http://127.0.0.1:8099';

/** Sends a command; throws the node's own error text when it fails. */
export async function cmd(name, args = {}) {
  const res = await fetch(nodeUrl, { method: 'POST', body: JSON.stringify({ cmd: name, ...args }) });
  const answer = await res.json();
  if (!answer.ok) throw new Error(`${name} ${JSON.stringify(args)}: ${answer.error}`);
  return answer;
}

export const click = text => cmd('click', { text });                         // a button by its text
export const type = (prompt, text) => cmd('type', { prompt, text });         // a field by its prompt text
export const combo = text => cmd('combo', { text });                         // the editable combo box (the server address)
export const wait = (text, seconds = 20) => cmd('await', { text, seconds }); // until a label or button with this text shows
export const waitStarting = async (start, seconds = 20) => (await cmd('awaitStarting', { start, seconds })).text;
export const texts = async () => (await cmd('texts')).texts;                 // what is on screen (to see where the app is)
export const openMenu = (...path) => cmd('openMenu', { path });              // opens a menu of the bar and leaves it open
export const closeMenus = () => cmd('closeMenus');
export const chooseMenu = (...path) => cmd('chooseMenu', { path });
export const snap = name => cmd('snap', { name });                           // the window with its open menus: out/<name>.png
export const snapDialog = name => cmd('snapDialog', { name });               // the newest dialog alone
export const dialogOpen = async () => (await cmd('dialogOpen')).open;
export const quit = () => cmd('quit').catch(() => {});

/** Runs a flow: prints a short error (and saves out/_fail.png) instead of a stack, never leaves the node running by itself. */
export async function main(fn) {
  try {
    await fn();
  } catch (e) {
    console.log('FAILED:', String(e.message || e).split('\n').slice(0, 4).join('\n  '));
    await snap('_fail').catch(() => {});
    process.exitCode = 1;
  }
}
