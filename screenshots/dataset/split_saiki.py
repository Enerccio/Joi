#!/usr/bin/env python3
"""Splits the Body mesh of the Saiki VRM into one node per primitive, so Joi has a toggle for each piece of clothing.

Saiki (a VRM 0.x, see SCREENSHOTS.md) has three meshes: Face, Hair and Body, and the clothes are primitives (one per
material) of the Body mesh, so Joi's toggles, which are nodes, could only hide the whole body. The primitives share their
vertex data and differ in their index data: a glTF can point one mesh per primitive at the same accessors, so the file's
binary part is not touched, only its JSON.

    python3 split_saiki.py <in.vrm> <out.vrm> [--names "<primitive index>=<name>,..."]

The result is a derivative of the model (credit and share-alike apply, see the manual's credits page): keep it in tmp/.
"""
import json, struct, sys

# what each primitive of the with-bag file is (Body mesh, by the order of its primitives)
# (found by the vertex positions of each primitive: 5 is the long diagonal of the strap, 6 the bag at the hip, 8 the ring at the neck,
# 9 a strand of hair behind the back; the cat ears are part of the Hair mesh)
NAMES = {0: 'Shoes', 1: 'Skirt', 2: 'Body', 3: 'Shirt', 4: 'Blazer', 5: 'Bag strap', 6: 'Bag', 7: 'Tie', 8: 'Scarf', 9: 'Back hair'}


def read(path):
    data = open(path, 'rb').read()
    assert data[:4] == b'glTF', 'not a GLB'
    jlen = struct.unpack('<I', data[12:16])[0]
    gltf = json.loads(data[20:20 + jlen])
    rest = data[20 + jlen:]          # the binary chunk(s), untouched
    return gltf, rest


def write(path, gltf, rest):
    js = json.dumps(gltf, separators=(',', ':')).encode('utf-8')
    js += b' ' * (-len(js) % 4)
    total = 12 + 8 + len(js) + len(rest)
    with open(path, 'wb') as f:
        f.write(b'glTF' + struct.pack('<II', 2, total))
        f.write(struct.pack('<I', len(js)) + b'JSON' + js)
        f.write(rest)


def main(argv):
    names = dict(NAMES)
    if '--names' in argv:
        i = argv.index('--names')
        for pair in argv[i + 1].split(','):
            k, v = pair.split('=', 1)
            names[int(k)] = v
        del argv[i:i + 2]
    src, dst = argv[1], argv[2]
    gltf, rest = read(src)
    body_node = next(i for i, n in enumerate(gltf['nodes']) if n.get('name', '').startswith('Body') and 'mesh' in n)
    node = gltf['nodes'][body_node]
    mesh = gltf['meshes'][node['mesh']]
    prims = mesh['primitives']
    assert len(prims) == len(names), f'{len(prims)} primitives, {len(names)} names'
    parents = [i for i, n in enumerate(gltf['nodes']) if body_node in n.get('children', [])]
    new_nodes = []
    for index, prim in enumerate(prims):
        gltf['meshes'].append({'name': names[index], 'primitives': [prim]})
        gltf['nodes'].append({'name': names[index], 'translation': node.get('translation', [0, 0, 0]),
                              'rotation': node.get('rotation', [0, 0, 0, 1]), 'scale': node.get('scale', [1, 1, 1]),
                              'mesh': len(gltf['meshes']) - 1, 'skin': node['skin']})
        new_nodes.append(len(gltf['nodes']) - 1)
    # the old node goes (a scene root or a child: the pieces take its place)
    if parents:
        for p in parents:
            kids = gltf['nodes'][p]['children']
            kids.remove(body_node)
            kids.extend(new_nodes)
    else:
        for scene in gltf['scenes']:
            if body_node in scene['nodes']:
                scene['nodes'].remove(body_node)
                scene['nodes'].extend(new_nodes)
    del node['mesh']
    del node['skin']
    node['name'] = 'Body (empty)'
    # VRM 0.x: the first person annotations name meshes by node, the blend shapes by mesh: neither names the body
    vrm = gltf.get('extensions', {}).get('VRM', {})
    for a in vrm.get('firstPerson', {}).get('meshAnnotations', []):
        assert a.get('mesh') != node.get('mesh'), 'a first person annotation names the Body mesh'
    write(dst, gltf, rest)
    print('wrote', dst, '-', len(new_nodes), 'nodes:', ', '.join(names[i] for i in range(len(prims))))


if __name__ == '__main__':
    main(sys.argv)
