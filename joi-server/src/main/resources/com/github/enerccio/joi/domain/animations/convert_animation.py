"""
Converts the animation of an FBX (Mixamo and the like) or a BVH into VRM Animation clips (.vrma), headless in Blender
with the "VRM format" add-on (extensions.blender.org, id vrm). Joi's server runs it (AnimationConversionService):

    Blender -b --factory-startup --python convert_animation.py -- <config.json>

The config (JSON):

    input      the FBX or BVH
    outputDir  where the clips go: one .vrma per take (an FBX can hold several), named 0.vrma, 1.vrma...
    report     where the report goes (JSON): {"clips": [{"file", "take", "frames", "fps"}], "humanoidMissing",
               "armature", "warnings", "error"}

The bones are mapped to VRM's humanoid by the add-on, which knows the names of Mixamo, Unreal, Biped, VRoid, MMD and
others, and guesses the rest from the shape of the skeleton. The add-on's exporter puts the skeleton in a T-pose and
writes the rotations of the humanoid bones against it, and the movement of the hips, so a clip plays on any body.
"""
import json
import os
import struct
import sys
import traceback

import addon_utils
import bpy

ADDON = "bl_ext.blender_org.vrm"

# the bones a clip can't do without: the add-on refuses a humanoid without them
REQUIRED_BONES = ("hips", "spine", "head", "left_upper_arm", "left_lower_arm", "left_hand", "right_upper_arm",
                  "right_lower_arm", "right_hand", "left_upper_leg", "left_lower_leg", "left_foot", "right_upper_leg",
                  "right_lower_leg", "right_foot")

report = {"clips": [], "humanoidMissing": [], "warnings": [], "armature": None}


def log(*args):
    print("[convert]", *args, flush=True)


def warn(text):
    report["warnings"].append(text)
    log("WARNING", text)


def write_report(cfg):
    with open(cfg["report"], "w", encoding="utf-8") as f:
        json.dump(report, f, indent=1)


def load_config():
    path = sys.argv[sys.argv.index("--") + 1]
    with open(path, encoding="utf-8") as f:
        return json.load(f)



def enable_vrm_addon():
    """Enables the VRM add-on, or fails saying why. addon_utils.enable does not raise when the add-on can't be loaded (it
    returns None and prints the error), and bpy.ops answers any name, so neither tells by itself: what the add-on registers
    on an armature does."""
    errors = []
    try:
        module = addon_utils.enable(ADDON, default_set=True, handle_error=errors.append)
    except Exception as e:
        module = None
        errors.append(e)
    if module is None or not hasattr(bpy.types.Armature, "vrm_addon_extension"):
        if errors:
            why = "%s: %s" % (type(errors[-1]).__name__, errors[-1])
        elif module is None:
            why = "it is not installed for this Blender and user folder"
        else:
            why = "it was loaded but did not register its properties"
        raise RuntimeError("the VRM add-on (VRM format, extensions.blender.org) could not be loaded in Blender %s "
                           "(user folder %s): %s" % (bpy.app.version_string, bpy.utils.resource_path("USER"), why))


def has_operator(group, name):
    """Whether the operator exists: bpy.ops.<group>.<name> is there for any name, its RNA type only for a real one."""
    try:
        getattr(getattr(bpy.ops, group), name).get_rna_type()
        return True
    except (KeyError, AttributeError):
        return False

def start():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    enable_vrm_addon()
    if not has_operator("export_scene", "vrma"):
        raise RuntimeError("the VRM add-on in this Blender can't export VRM Animation: update it")


def import_file(path):
    extension = os.path.splitext(path)[1].lower()
    if extension == ".bvh":
        bpy.ops.import_anim.bvh(filepath=path, update_scene_fps=True, update_scene_duration=True)
    elif extension == ".fbx":
        bpy.ops.import_scene.fbx(filepath=path, use_anim=True)
    else:
        raise RuntimeError("not an FBX or a BVH: " + os.path.basename(path))
    armatures = [o for o in bpy.data.objects if o.type == "ARMATURE"]
    if not armatures:
        raise RuntimeError("the file has no skeleton")
    # a file with a body and props can bring several skeletons: the clip is on the one with the most bones
    armatures.sort(key=lambda a: len(a.data.bones), reverse=True)
    if len(armatures) > 1:
        warn("%d skeletons; the one with the most bones (%s) is used" % (len(armatures), armatures[0].name))
    report["armature"] = armatures[0].name
    return armatures[0]


def takes(arm):
    """The actions of the file that move the skeleton, the one it wears first."""
    current = arm.animation_data.action if arm.animation_data else None
    actions = [a for a in bpy.data.actions if a.users > 0 or a == current]
    # an FBX imports each take as an action named "<armature>|<take>"; actions of other objects are left out
    own = [a for a in actions if a == current or a.name.startswith(arm.name + "|")]
    found = own or actions
    found.sort(key=lambda a: (a != current, a.name))
    return found


def setup_humanoid(arm):
    arm.data.vrm_addon_extension.spec_version = "1.0"
    bpy.context.view_layer.objects.active = arm
    bpy.ops.vrm.assign_vrm1_humanoid_human_bones_automatically(armature_object_name=arm.name)
    hb = arm.data.vrm_addon_extension.vrm1.humanoid.human_bones
    missing = [name for name in REQUIRED_BONES if not getattr(hb, name).node.bone_name]
    report["humanoidMissing"] = missing
    if missing:
        raise RuntimeError("the humanoid bones were not all found: " + ", ".join(missing)
                           + " (bones: " + ", ".join(b.name for b in arm.data.bones[:40]) + ")")


def take_name(arm, action):
    """The take's own name: an FBX's actions are "<armature>|<take>", sometimes twice over."""
    return action.name.split("|")[-1]


def fix_rest_hips(path):
    """
    A BVH (and an FBX made from one) often has its hips at the origin in the rest pose and their height only in the
    motion. A player scales the movement of the hips by the body's hip height over the clip's, so a rest at the floor
    breaks the clip: the rest of the hips becomes where they are in the first frame.
    """
    with open(path, "rb") as f:
        data = f.read()
    length = struct.unpack("<I", data[12:16])[0]
    gltf = json.loads(data[20:20 + length])
    binary = data[20 + length:]
    hips = gltf["extensions"]["VRMC_vrm_animation"]["humanoid"]["humanBones"]["hips"]["node"]
    node = gltf["nodes"][hips]
    rest = node.get("translation", [0.0, 0.0, 0.0])
    first = None
    for channel in gltf["animations"][0]["channels"] if gltf.get("animations") else []:
        if channel["target"]["node"] == hips and channel["target"]["path"] == "translation":
            sampler = gltf["animations"][0]["samplers"][channel["sampler"]]
            accessor = gltf["accessors"][sampler["output"]]
            view = gltf["bufferViews"][accessor["bufferView"]]
            offset = 8 + view.get("byteOffset", 0) + accessor.get("byteOffset", 0)
            first = list(struct.unpack("<3f", binary[offset:offset + 12]))
    if first is None:
        return
    size = sum(v * v for v in first) ** 0.5
    if size == 0 or sum(v * v for v in rest) ** 0.5 > size * 0.01:
        return
    node["translation"] = first
    log("the hips rest at the floor: their rest is the first frame's", first)
    text = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    text += b" " * ((4 - len(text) % 4) % 4)
    total = 12 + 8 + len(text) + len(binary)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(text), 0x4E4F534A))
        f.write(text)
        f.write(binary)


def export(arm, action, index, cfg):
    if arm.animation_data is None:
        arm.animation_data_create()
    arm.animation_data.action = action
    start_frame, end_frame = (int(round(f)) for f in action.frame_range)
    scene = bpy.context.scene
    scene.frame_start = start_frame
    scene.frame_end = max(end_frame, start_frame + 1)
    path = os.path.join(cfg["outputDir"], "%d.vrma" % index)
    bpy.ops.object.select_all(action="DESELECT")
    result = bpy.ops.export_scene.vrma(filepath=path, armature_object_name=arm.name)
    if "FINISHED" not in result or not os.path.isfile(path):
        raise RuntimeError("the VRM Animation export failed: " + str(result))
    fix_rest_hips(path)
    fps = scene.render.fps / scene.render.fps_base
    report["clips"].append({"file": os.path.basename(path), "take": take_name(arm, action),
                            "frames": scene.frame_end - scene.frame_start + 1, "fps": fps})
    log("clip", path, take_name(arm, action), scene.frame_start, scene.frame_end, fps)


def main():
    cfg = load_config()
    try:
        log("step import")
        start()
        arm = import_file(cfg["input"])
        actions = takes(arm)
        if not actions:
            raise RuntimeError("the file has no animation")
        log("takes", [a.name for a in actions])
        log("step humanoid")
        setup_humanoid(arm)
        write_report(cfg)
        log("step export")
        os.makedirs(cfg["outputDir"], exist_ok=True)
        for index, action in enumerate(actions):
            export(arm, action, index, cfg)
            write_report(cfg)
        log("done")
    except Exception as e:
        report["error"] = str(e)
        write_report(cfg)
        traceback.print_exc()
        log("FAILED", e)
        sys.exit(1)


main()
