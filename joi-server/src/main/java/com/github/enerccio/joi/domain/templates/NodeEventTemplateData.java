package com.github.enerccio.joi.domain.templates;

import com.github.enerccio.joi.loc.J_L;

/**
 * The data of the events a node brings ({@code JoiPromptField.FIRST_BOOT_EVENT}, {@code CONNECTED_EVENT},
 * {@code LOOKING_GLASS_ENTERED_EVENT}, {@code LOOKING_GLASS_EXITED_EVENT}, {@code SERVER_RESTARTED_EVENT}): what she is told, as the user's turn, when
 * she first wakes up on a node, when the user comes back to her there, or moves her onto a Looking Glass and back.
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

    /** What happened to the server (the event of a server that was down): {@code was shut down}, {@code went down}. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_HOW_LOST)
    private String howLost;

    /** How long it was gone, in words ({@code 3 minutes}); empty when the node could not say. */
    @JoiTemplateDescription(loc = J_L.DESC_TEMPLATE_DOWN_FOR)
    private String downFor;

    public String getHowLost() {
        return howLost;
    }

    public void setHowLost(String howLost) {
        this.howLost = howLost;
    }

    public String getDownFor() {
        return downFor;
    }

    public void setDownFor(String downFor) {
        this.downFor = downFor;
    }

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
