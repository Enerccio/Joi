#!/usr/bin/env python3
"""Prints the image table of SCREENSHOTS.md: which flow takes which picture, on which state, and where the manual uses it."""
import glob, os, re, subprocess
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
repo = os.path.join(root, '..')
# the state a flow runs on: a chain flow starts from the step before it (bin/chain.txt), the shooting flows as bin/shoot-all.sh says
state = {'shoot-workspace': 'any step from 01-admin (the last one)', 'node-first-start': '01-admin, a node that was never started',
         'shoot-settings': 'any step from 07-body (the last one)', 'shoot-joi': 'any step from 07-body (the last one)',
         'shoot-model': 'any step from 07-body (the last one)', 'node-chat': 'step 09-conversation, the fake model, a node that was never started'}
prev = '00-empty'
for line in open(os.path.join(root, 'bin', 'chain.txt'), encoding='utf-8'):
    if not line.strip() or line.startswith('#'): continue
    flow, step = line.split()
    state[flow[:-4]] = 'step ' + prev
    prev = step
rows = {}
for flow in sorted(glob.glob(os.path.join(root, 'flows', '*.mjs'))):
    base = os.path.basename(flow)[:-4]
    if base.startswith('_'): continue
    src = open(flow, encoding='utf-8').read()
    # the web flows: snap(page, 'name'), snapDialog(page, 'name'); the node flows: snap('name'), snapDialog('name')
    for m in re.finditer(r"snap(?:Dialog|Popover)?\(\s*(?:page\s*,\s*)?'([^']+)'", src):
        n = m.group(1)
        if not n.startswith('_') and not n.endswith('-'): rows[n] = base
    # tabs taken in a loop: [label, 'name'] pairs
    for n in re.findall(r"\['[^']+',\s*'(tab-[a-z-]+)'\]", src):
        rows[n] = base
# pictures whose names a flow builds in a loop (the pattern above does not see them)
for n in ('outfit-school-uniform', 'outfit-light', 'outfit-cold-day'):
    rows[n] = '06-body'
# cropped from the node's pictures by bin/crop-node.sh
for n in ('node-controls', 'node-looking-glass-tuning'):
    rows[n] = 'node-chat'
def used(name):
    out = subprocess.run(['grep', '-rl', 'images/%s.png' % name, os.path.join(repo, 'manual'), '--include=*.md'],
                         capture_output=True, text=True).stdout.split()
    return ', '.join(sorted(os.path.relpath(p, repo) for p in out)) or '-'
print('| Picture | Flow | State | Used in |\n|---|---|---|---|')
for n in sorted(rows):
    flow = rows[n]
    print('| `%s.png` | `%s` | %s | %s |' % (n, flow, state.get(flow, ''), used(n)))
