// What the fake model "sees" in the pictures of Saiki (see SCREENSHOTS.md): the text a multimodal model would write for the
// appearance and for the outfits. The pictures themselves are never looked at: the answer depends on the prompt only.

// How she looks (the base state: the layers that can be switched are off in the renders, but the model is described as worn).
export const description =
  'You are a young woman of slight build and average height, with a gentle, attentive face, large violet eyes and long dark ' +
  'brown hair tied in two low twin tails. Soft white cat ears sit on top of your head. You wear a school uniform: a black blazer ' +
  'over a pale knit vest and white shirt, a red tartan scarf with an orange bow at the neck, a dark blue pleated skirt, black ' +
  'tights and dark loafers. A small navy shoulder bag hangs at your hip on a thin white strap.';

// the names she gives the toggles: the node's name in the model → her name for it
export const toggleNames = {
  'Shoes': 'shoes', 'Skirt': 'skirt', 'Body': 'body', 'Shirt': 'shirt', 'Blazer': 'blazer', 'Bag strap': 'bag strap',
  'Bag': 'shoulder bag', 'Tie': 'bow', 'Scarf': 'scarf', 'Back hair': 'back hair', 'Face.001': 'face', 'Hair001.001': 'hair',
};

// the outfits of the body: name → what she wears (the answer of the photoshoot)
export const outfits = {
  'School uniform':
    'You wear a full school uniform: a black blazer over a pale knit vest and white shirt, an orange bow, a dark blue pleated ' +
    'skirt, black tights and dark loafers. A red tartan scarf is wound at your neck and a navy bag hangs at your hip. Your twin ' +
    'tails and cat ears complete a neat, studious look.',
  'Light':
    'You wear the uniform without its extras: the black blazer over the pale knit vest and white shirt, the orange bow, the dark ' +
    'blue pleated skirt, black tights and loafers. There is no scarf and no bag, so your neck is free and your hands are empty.',
  'Cold day':
    'You are wrapped up warm: a black blazer over the knit vest, a thick red tartan scarf up to your chin, the pleated skirt with ' +
    'black tights and loafers. You carry no bag, your hands are free, and the cat ears peek out above the scarf.',
};
