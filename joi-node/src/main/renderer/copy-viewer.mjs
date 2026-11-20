// The renderer is the server's <joi-model-viewer> (joi-server/src/main/frontend/joi-model-viewer.js): copied next to
// the page before the build, so its imports resolve from this project's node_modules.
import {copyFileSync, mkdirSync} from 'node:fs';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
mkdirSync(resolve(here, 'src/generated'), {recursive: true});
copyFileSync(resolve(here, '../../../../joi-server/src/main/frontend/joi-model-viewer.js'),
  resolve(here, 'src/generated/joi-model-viewer.js'));
