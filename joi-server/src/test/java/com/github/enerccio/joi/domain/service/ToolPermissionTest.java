package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.ToolMetadata;
import com.github.enerccio.joi.domain.service.ToolPermissionService.Chain;
import com.github.enerccio.joi.domain.tools.ServerTool;
import com.github.enerccio.joi.domain.tools.ToolPermission;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.test.PlatformTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The chain of a tool's permission (the assistant, the user, the administrator, then the tool's own) with the
 * administrator's value as a ceiling, the options along the same places, who may write which place, and the secrets.
 */
class ToolPermissionTest extends PlatformTestBase {

    private static final ServerTool TOOL = ServerTool.WEB_SEARCH;

    @Autowired
    private ToolMetadataService metadataService;
    @Autowired
    private ToolPermissionService permissionService;
    @Autowired
    private JoiService joiService;

    private Joi joi;
    private User me;

    @BeforeEach
    void setUp() throws Exception {
        // the administrator's place is the same for every test: leave it empty
        loginAdmin();
        for (ToolMetadata left : metadataService.findAll(ToolScope.APP, null)) {
            metadataService.delete(left, true);
        }
        me = login();
        joi = joiService.create("Kairo");
    }

    private void set(ToolScope scope, ToolPermission permission) throws Exception {
        ToolMetadata metadata = metadataService.edit(scope, joi, TOOL);
        metadata.setPermission(permission);
        metadataService.save(metadata);
    }

    /** As the administrator, back as the user after. */
    private void setAsAdmin(ToolPermission permission) throws Exception {
        loginAdmin();
        set(ToolScope.APP, permission);
        loginAs(me);
    }

    private ToolPermission effective() throws Exception {
        return permissionService.effective(joi, TOOL);
    }

    @Test
    void aToolNobodySaidAnythingAboutIsWhatItIsBuiltAs() throws Exception {
        Chain chain = permissionService.chain(joi, TOOL);
        assertThat(chain.assistant()).isNull();
        assertThat(chain.user()).isNull();
        assertThat(chain.admin()).isNull();
        assertThat(chain.effective()).isEqualTo(ToolPermission.ASK).isEqualTo(TOOL.fallback());
        assertThat(chain.source()).isNull();
        assertThat(chain.capped()).isFalse();
    }

    @Test
    void theFirstOneSetWinsAssistantThenUserThenAdministrator() throws Exception {
        setAsAdmin(ToolPermission.ALLOW);
        assertThat(permissionService.chain(joi, TOOL).source()).isEqualTo(ToolScope.APP);
        // the administrator's is also the default of everybody, even when it is looser than the tool's own
        assertThat(effective()).isEqualTo(ToolPermission.ALLOW);

        set(ToolScope.USER, ToolPermission.ASK);
        assertThat(effective()).isEqualTo(ToolPermission.ASK);
        assertThat(permissionService.chain(joi, TOOL).source()).isEqualTo(ToolScope.USER);

        set(ToolScope.JOI, ToolPermission.ALLOW);
        Chain chain = permissionService.chain(joi, TOOL);
        assertThat(chain.effective()).isEqualTo(ToolPermission.ALLOW);
        assertThat(chain.source()).isEqualTo(ToolScope.JOI);
        assertThat(chain.assistant()).isEqualTo(ToolPermission.ALLOW);
        assertThat(chain.user()).isEqualTo(ToolPermission.ASK);
        assertThat(chain.admin()).isEqualTo(ToolPermission.ALLOW);
    }

    @Test
    void whatIsSetBelowTheAdministratorCanOnlyBeStricter() throws Exception {
        setAsAdmin(ToolPermission.ASK);
        set(ToolScope.USER, ToolPermission.ALLOW);
        Chain chain = permissionService.chain(joi, TOOL);
        assertThat(chain.value()).isEqualTo(ToolPermission.ALLOW);
        assertThat(chain.effective()).isEqualTo(ToolPermission.ASK);
        assertThat(chain.capped()).isTrue();
        assertThat(chain.source()).isEqualTo(ToolScope.APP);

        // stricter is fine
        set(ToolScope.JOI, ToolPermission.DENY);
        assertThat(effective()).isEqualTo(ToolPermission.DENY);
        assertThat(permissionService.chain(joi, TOOL).capped()).isFalse();

        // an administrator's deny locks the tool
        setAsAdmin(ToolPermission.DENY);
        set(ToolScope.JOI, ToolPermission.ALLOW);
        assertThat(effective()).isEqualTo(ToolPermission.DENY);
    }

    @Test
    void nothingSetByTheUserLoosensWhatTheAdministratorSetsFromTheDefault() throws Exception {
        // the tool is ask when nothing is said; an administrator's ask is the same and still a ceiling
        setAsAdmin(ToolPermission.ASK);
        set(ToolScope.JOI, ToolPermission.ALLOW);
        assertThat(effective()).isEqualTo(ToolPermission.ASK);
        // without an assistant the chain has no assistant link: the user's own settings page
        Chain chain = permissionService.chain(null, TOOL);
        assertThat(chain.assistant()).isNull();
        assertThat(chain.effective()).isEqualTo(ToolPermission.ASK);
    }

    @Test
    void onlyAdministratorsWriteTheAdministratorsPlace() throws Exception {
        ToolMetadata metadata = metadataService.edit(ToolScope.APP, null, TOOL);
        metadata.setPermission(ToolPermission.ALLOW);
        assertThatThrownBy(() -> metadataService.save(metadata)).isInstanceOf(SecurityException.class);

        loginAdmin();
        metadataService.save(metadata);
        // everybody reads it: it is part of every chain
        loginAs(me);
        assertThat(metadataService.find(ToolScope.APP, null, TOOL)).isNotNull();
    }

    @Test
    void thePlacesOfOtherUsersAreNotSeenNorWritten() throws Exception {
        set(ToolScope.USER, ToolPermission.DENY);
        User other = createUser();
        loginAs(other);
        assertThat(metadataService.find(ToolScope.USER, null, TOOL)).isNull();
        assertThat(permissionService.chain(null, TOOL).user()).isNull();
        // nor an assistant that is not theirs
        assertThatThrownBy(() -> metadataService.find(ToolScope.JOI, joi, TOOL)).isInstanceOf(SecurityException.class);
        ToolMetadata foreign = new ToolMetadata();
        foreign.setScope(ToolScope.JOI);
        foreign.setJoi(joi);
        foreign.setToolKey(TOOL.function());
        assertThatThrownBy(() -> metadataService.save(foreign)).isInstanceOf(SecurityException.class);
        loginAs(me);
        assertThat(metadataService.find(ToolScope.USER, null, TOOL).getPermission()).isEqualTo(ToolPermission.DENY);
    }

    @Test
    void aPlaceHasOneRowPerTool() throws Exception {
        set(ToolScope.USER, ToolPermission.ALLOW);
        ToolMetadata second = new ToolMetadata();
        second.setScope(ToolScope.USER);
        second.setToolKey(TOOL.function());
        assertThatThrownBy(() -> metadataService.save(second)).isInstanceOf(IllegalStateException.class);
        // changing the row is not a second one, and a null permission inherits again
        set(ToolScope.USER, null);
        assertThat(metadataService.find(ToolScope.USER, null, TOOL).getPermission()).isNull();
        assertThat(metadataService.findAll(ToolScope.USER, null)).hasSize(1);
        // another tool is another row
        ToolMetadata page = metadataService.edit(ToolScope.USER, null, ServerTool.FETCH_PAGE);
        page.setPermission(ToolPermission.ALLOW);
        metadataService.save(page);
        assertThat(metadataService.findAll(ToolScope.USER, null)).hasSize(2);
        assertThat(permissionService.effective(joi, ServerTool.FETCH_PAGE)).isEqualTo(ToolPermission.ALLOW);
        assertThat(effective()).isEqualTo(ToolPermission.ASK);
    }

    @Test
    void theOptionsAreThoseOfTheFirstPlaceThatHasAny() throws Exception {
        assertThat(permissionService.config(joi, TOOL).metadata()).isNull();

        loginAdmin();
        ToolMetadata app = metadataService.edit(ToolScope.APP, null, TOOL);
        app.config().addProperty("maxResults", 5);
        metadataService.save(app);
        loginAs(me);
        ToolPermissionService.Config config = permissionService.config(joi, TOOL);
        assertThat(config.from()).isEqualTo(ToolScope.APP);
        assertThat(config.metadata().config().get("maxResults").getAsInt()).isEqualTo(5);

        // a user's row that only has a permission has no options to speak of: the administrator's still apply
        set(ToolScope.USER, ToolPermission.ALLOW);
        assertThat(permissionService.config(joi, TOOL).from()).isEqualTo(ToolScope.APP);

        ToolMetadata own = metadataService.edit(ToolScope.USER, null, TOOL);
        own.config().addProperty("maxResults", 3);
        metadataService.save(own);
        assertThat(permissionService.config(joi, TOOL).from()).isEqualTo(ToolScope.USER);

        ToolMetadata mine = metadataService.edit(ToolScope.JOI, joi, TOOL);
        mine.config().addProperty("maxResults", 1);
        metadataService.save(mine);
        config = permissionService.config(joi, TOOL);
        assertThat(config.from()).isEqualTo(ToolScope.JOI);
        assertThat(config.metadata().config().get("maxResults").getAsInt()).isEqualTo(1);
    }

    @Test
    void aSecretIsKeptProtectedAndReadBack() throws Exception {
        ToolMetadata metadata = metadataService.edit(ToolScope.USER, null, TOOL);
        assertThat(metadataService.hasSecret(metadata, "brave")).isFalse();
        metadataService.setSecret(metadata, "brave", "  sk-very-secret-key  ");
        assertThat(metadataService.hasSecret(metadata, "brave")).isTrue();
        assertThat(metadata.protectedSecrets().get("brave").getAsString()).doesNotContain("sk-very-secret-key");
        metadataService.save(metadata);

        ToolMetadata read = metadataService.find(ToolScope.USER, null, TOOL);
        assertThat(metadataService.secret(read, "brave")).isEqualTo("sk-very-secret-key");
        assertThat(new String(read.getExtendedContent())).doesNotContain("sk-very-secret-key");

        metadataService.setSecret(read, "brave", " ");
        assertThat(metadataService.hasSecret(read, "brave")).isFalse();
        assertThat(metadataService.secret(read, "brave")).isNull();
    }
}
