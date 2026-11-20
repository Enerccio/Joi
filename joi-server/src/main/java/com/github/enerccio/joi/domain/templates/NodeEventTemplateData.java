package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the events a node brings ({@code JoiPromptField.FIRST_BOOT_EVENT}, {@code CONNECTED_EVENT}): what she is
 * told, as the user's turn, when she first wakes up on a node or when the user comes back to her there.
 */
public class NodeEventTemplateData extends JoiTemplateData {

    /** The name of the node ({@code desk}). */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_NODE)
    private String node;

    /** What the node is ({@code desktop}, {@code phone}...); empty when it did not say. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_NODE_KIND)
    private String nodeKind;

    /** When she last talked with the user, in words ({@code 3 hours ago}); empty for never. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_LAST_CHATTED)
    private String lastChatted;

    public String getNode() {
        return node;
    }

    public void setNode(String node) {
        this.node = node;
    }

    public String getNodeKind() {
        return nodeKind;
    }

    public void setNodeKind(String nodeKind) {
        this.nodeKind = nodeKind;
    }

    public String getLastChatted() {
        return lastChatted;
    }

    public void setLastChatted(String lastChatted) {
        this.lastChatted = lastChatted;
    }
}
