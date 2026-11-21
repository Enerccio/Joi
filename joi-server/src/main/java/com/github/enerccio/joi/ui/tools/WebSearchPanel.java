package com.github.enerccio.joi.ui.tools;

import com.flowingcode.vaadin.addons.fontawesome.FontAwesome.Solid;
import com.github.enerccio.joi.domain.service.WebToolSettingsService;
import com.github.enerccio.joi.domain.service.WebToolSettingsService.Pages;
import com.github.enerccio.joi.domain.service.WebToolSettingsService.Search;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.joi.domain.web.PageConfig;
import com.github.enerccio.joi.domain.web.PageSourceType;
import com.github.enerccio.joi.domain.web.SearchSource;
import com.github.enerccio.joi.domain.web.WebException;
import com.github.enerccio.joi.domain.web.WebSearchConfig;
import com.github.enerccio.joi.loc.J_L;
import com.github.enerccio.joi.ui.JoiCssStyles;
import com.github.enerccio.marginalia.CssStyles;
import com.github.enerccio.marginalia.loc.L;
import com.github.enerccio.marginalia.loc.Localization;
import com.github.enerccio.marginalia.ui.dialogs.ConfirmDialog;
import com.github.enerccio.marginalia.ui.widgets.Notification;
import com.github.enerccio.marginalia.utils.UIUtils;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The options of {@code web_search} and {@code fetch_page} in one place of the chain: the administrator's (the default of
 * everybody) or the user's own (which replaces the administrator's as a whole). The search sources in a grid (in the
 * order they are tried), the options of the searches, and how a page is read. Saved with one button; secrets are never
 * shown, a saved one is only said to be there.
 */
@Configurable
public class WebSearchPanel {

    @Autowired
    private Localization loc;

    @Autowired
    private WebToolSettingsService settings;

    private final ToolScope scope;
    private final List<SearchSource> sources = new ArrayList<>();
    /** What changes in the secrets on save: the name of the secret and its new value, blank to remove. */
    private final Map<String, String> secretChanges = new LinkedHashMap<>();
    private final Set<String> savedKeys = new HashSet<>();
    private boolean proxyAuthSaved;
    private boolean pageProxyAuthSaved;
    private boolean tokenSaved;

    private Span inherited;
    private Button useInherited;
    private Grid<SearchSource> grid;
    private TextField proxy;
    private PasswordField proxyAuth;
    private IntegerField timeout;
    private IntegerField maxResults;
    private TextField language;
    private Checkbox safeSearch;
    private TextField userAgent;
    private Checkbox localOnly;
    private IntegerField maxSearches;
    private IntegerField maxPages;
    private Select<PageSourceType> pageType;
    private TextField pageUrl;
    private PasswordField pageToken;
    private Checkbox pageLocal;
    private TextField pageProxy;
    private PasswordField pageProxyAuth;
    private IntegerField pageTimeout;
    private IntegerField pageMaxChars;
    private Checkbox pageAllowPrivate;
    /** Whether the administrator allows the server's own network; below the administrator the box is shown only then. */
    private boolean privateAllowed;
    private TextField testUrl;
    private Span testResult;

    public WebSearchPanel(ToolScope scope) {
        this.scope = scope;
    }

    public Component create() throws Exception {
        Span help = new Span(loc.getValue(scope == ToolScope.APP ? J_L.HELP_WEB_SEARCH_APP : J_L.HELP_WEB_SEARCH_USER));
        help.addClassName(CssStyles.HINT_TEXT);
        inherited = new Span(loc.getValue(J_L.MSG_WEB_SEARCH_INHERITED));
        inherited.addClassName(CssStyles.HINT_TEXT);
        useInherited = new Button(loc.getValue(J_L.LABEL_USE_INHERITED), Solid.UNDO.create(), e ->
                ConfirmDialog.show(loc.getValue(L.MSG_CONFIRM_DELETE), this::clear));
        useInherited.setVisible(scope == ToolScope.USER);

        Button add = new Button(loc.getValue(J_L.LABEL_ADD_SOURCE), Solid.PLUS.create(), e -> edit(null));
        add.setThemeName("primary");
        HorizontalLayout sourcesHead = new HorizontalLayout(new H4(loc.getValue(J_L.LABEL_SEARCH_SOURCES)), add);
        sourcesHead.setWidthFull();
        sourcesHead.setAlignItems(FlexComponent.Alignment.CENTER);
        sourcesHead.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

        grid = new Grid<>();
        grid.addClassName(JoiCssStyles.WEB_SOURCES);
        grid.setAllRowsVisible(true);
        grid.addColumn(SearchSource::name).setHeader(loc.getValue(L.LABEL_NAME)).setFlexGrow(1);
        grid.addColumn(source -> WebSourceDialog.label(loc, source.type())).setHeader(loc.getValue(J_L.LABEL_SOURCE_TYPE)).setFlexGrow(1);
        grid.addColumn(source -> StringUtils.defaultIfBlank(String.join(", ", source.sites()), StringUtils.defaultString(source.url())))
                .setHeader(loc.getValue(J_L.LABEL_SOURCE_WHERE)).setFlexGrow(1);
        grid.addColumn(source -> loc.getValue(source.enabled() ? L.LABEL_YES : L.LABEL_NO)).setHeader(loc.getValue(J_L.LABEL_SOURCE_ENABLED))
                .setFlexGrow(0).setWidth("110px");
        grid.addComponentColumn(this::actions).setHeader("").setFlexGrow(0).setWidth("240px");
        grid.setEmptyStateText(loc.getValue(J_L.MSG_NO_SOURCES));

        FormLayout options = new FormLayout();
        options.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("40em", 2));
        proxy = new TextField(loc.getValue(J_L.LABEL_WEB_PROXY));
        proxyAuth = secret(J_L.LABEL_WEB_PROXY_AUTH);
        timeout = number(J_L.LABEL_WEB_TIMEOUT, 1, 300);
        maxResults = number(J_L.LABEL_WEB_MAX_RESULTS, 1, 50);
        language = new TextField(loc.getValue(J_L.LABEL_WEB_LANGUAGE));
        userAgent = new TextField(loc.getValue(J_L.LABEL_WEB_USER_AGENT));
        maxSearches = number(J_L.LABEL_WEB_MAX_SEARCHES, 1, 50);
        maxPages = number(J_L.LABEL_WEB_MAX_PAGES, 1, 50);
        safeSearch = new Checkbox(loc.getValue(J_L.LABEL_WEB_SAFE_SEARCH));
        localOnly = new Checkbox(loc.getValue(J_L.LABEL_WEB_LOCAL_ONLY));
        options.add(proxy, proxyAuth, timeout, maxResults, language, userAgent, maxSearches, maxPages, safeSearch, localOnly);

        FormLayout pages = new FormLayout();
        pages.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("40em", 2));
        pageType = new Select<>();
        pageType.setLabel(loc.getValue(J_L.LABEL_PAGE_TYPE));
        pageType.setItems(PageSourceType.values());
        pageType.setItemLabelGenerator(this::label);
        pageType.addValueChangeListener(e -> pageTypeChanged());
        pageUrl = new TextField(loc.getValue(J_L.LABEL_PAGE_URL));
        pageToken = secret(J_L.LABEL_PAGE_TOKEN);
        pageLocal = new Checkbox(loc.getValue(J_L.LABEL_SOURCE_LOCAL));
        pageProxy = new TextField(loc.getValue(J_L.LABEL_PAGE_PROXY));
        pageProxyAuth = secret(J_L.LABEL_WEB_PROXY_AUTH);
        pageTimeout = number(J_L.LABEL_WEB_TIMEOUT, 1, 300);
        pageMaxChars = number(J_L.LABEL_PAGE_MAX_CHARS, 1000, 200_000);
        pageAllowPrivate = new Checkbox(loc.getValue(J_L.LABEL_PAGE_ALLOW_PRIVATE));
        pageAllowPrivate.setHelperText(loc.getValue(J_L.HELP_PAGE_ALLOW_PRIVATE));
        pages.add(pageType, pageUrl, pageToken, pageLocal, pageProxy, pageProxyAuth, pageTimeout, pageMaxChars, pageAllowPrivate);

        testUrl = new TextField(loc.getValue(J_L.LABEL_TEST_PAGE_URL));
        testUrl.setWidth("28rem");
        Button testPage = new Button(loc.getValue(J_L.LABEL_TEST_PAGE), e -> testPage());
        HorizontalLayout testRow = new HorizontalLayout(testUrl, testPage);
        testRow.setAlignItems(FlexComponent.Alignment.END);
        testRow.setPadding(false);
        testResult = new Span();
        testResult.addClassName(JoiCssStyles.TEST_RESULT);

        Button save = new Button(loc.getValue(L.LABEL_SAVE), Solid.SAVE.create(), e -> save());
        save.setThemeName("primary");

        VerticalLayout layout = new VerticalLayout(help, inherited, useInherited, sourcesHead, grid,
                new H4(loc.getValue(J_L.LABEL_WEB_OPTIONS)), options, new H4(loc.getValue(J_L.LABEL_PAGE_READER)), pages, testRow, testResult, save);
        layout.setWidthFull();
        layout.setPadding(false);
        load();
        return layout;
    }

    private PasswordField secret(J_L label) {
        PasswordField field = new PasswordField(loc.getValue(label));
        field.setAutocomplete(com.vaadin.flow.component.textfield.Autocomplete.OFF);
        return field;
    }

    private IntegerField number(J_L label, int min, int max) {
        IntegerField field = new IntegerField(loc.getValue(label));
        field.setMin(min);
        field.setMax(max);
        field.setStepButtonsVisible(true);
        return field;
    }

    private String label(PageSourceType type) {
        return loc.getValue(switch (type) {
            case DIRECT -> J_L.LABEL_PAGE_TYPE_DIRECT;
            case CRAWL4AI -> J_L.LABEL_PAGE_TYPE_CRAWL4AI;
            case CRAWL4AI_PROXY -> J_L.LABEL_PAGE_TYPE_CRAWL4AI_PROXY;
        });
    }

    private void pageTypeChanged() {
        PageSourceType type = pageType.getValue();
        boolean reader = type != null && type != PageSourceType.DIRECT;
        pageUrl.setVisible(reader);
        pageToken.setVisible(reader);
        pageLocal.setVisible(reader);
        pageProxy.setVisible(!reader);
        pageProxyAuth.setVisible(!reader);
        pageAllowPrivate.setVisible(!reader && (scope == ToolScope.APP || privateAllowed));
    }

    /** Shows what the place keeps (the defaults when it keeps nothing). */
    public void refresh() throws Exception {
        load();
    }

    private void load() throws Exception {
        Search search = settings.searchIn(scope, null);
        Pages pages = settings.pagesIn(scope, null);
        privateAllowed = scope == ToolScope.APP || settings.privateAllowed();
        sources.clear();
        sources.addAll(search.config().sources());
        secretChanges.clear();
        savedKeys.clear();
        search.keys().keySet().forEach(savedKeys::add);
        proxyAuthSaved = search.proxyAuth() != null;
        pageProxyAuthSaved = pages.proxyAuth() != null;
        tokenSaved = pages.token() != null;
        WebSearchConfig config = search.config();
        proxy.setValue(StringUtils.defaultString(config.proxy()));
        proxyAuth.clear();
        proxyAuth.setHelperText(proxyAuthSaved ? loc.getValue(J_L.HELP_WEB_PROXY_AUTH_SAVED) : null);
        timeout.setValue(config.timeoutSeconds());
        maxResults.setValue(config.maxResults());
        language.setValue(StringUtils.defaultString(config.language()));
        safeSearch.setValue(config.safeSearch());
        userAgent.setValue(StringUtils.defaultString(config.userAgent()));
        localOnly.setValue(config.localOnly());
        maxSearches.setValue(config.maxSearches());
        maxPages.setValue(config.maxPages());
        PageConfig page = pages.config();
        pageType.setValue(page.type());
        pageUrl.setValue(StringUtils.defaultString(page.url()));
        pageToken.clear();
        pageToken.setHelperText(tokenSaved ? loc.getValue(J_L.HELP_WEB_PROXY_AUTH_SAVED) : null);
        pageLocal.setValue(page.local());
        pageProxy.setValue(StringUtils.defaultString(page.proxy()));
        pageProxyAuth.clear();
        pageProxyAuth.setHelperText(pageProxyAuthSaved ? loc.getValue(J_L.HELP_WEB_PROXY_AUTH_SAVED) : null);
        pageTimeout.setValue(page.timeoutSeconds());
        pageMaxChars.setValue(page.maxChars());
        pageAllowPrivate.setValue(page.allowPrivate());
        pageTypeChanged();
        boolean own = search.from() != null || pages.from() != null;
        inherited.setVisible(scope == ToolScope.USER && !own);
        useInherited.setVisible(scope == ToolScope.USER && own);
        grid.setItems(sources);
    }

    private Component actions(SearchSource source) {
        int index = sources.indexOf(source);
        Button up = new Button(Solid.ARROW_UP.create(), e -> move(source, -1));
        up.setEnabled(index > 0);
        up.setThemeName("tertiary");
        Button down = new Button(Solid.ARROW_DOWN.create(), e -> move(source, 1));
        down.setEnabled(index < sources.size() - 1);
        down.setThemeName("tertiary");
        Button edit = new Button(Solid.PEN.create(), e -> edit(source));
        edit.setTooltipText(loc.getValue(L.LABEL_EDIT));
        Button delete = new Button(Solid.TRASH.create(), e -> {
            sources.remove(source);
            secretChanges.remove(WebSearchConfig.keyName(source));
            grid.setItems(sources);
        });
        delete.setThemeName("error tertiary");
        delete.setTooltipText(loc.getValue(L.LABEL_DELETE));
        return new HorizontalLayout(up, down, edit, delete);
    }

    private void move(SearchSource source, int by) {
        int index = sources.indexOf(source);
        sources.remove(index);
        sources.add(index + by, source);
        grid.setItems(sources);
    }

    private void edit(SearchSource source) {
        String keyName = source == null ? null : WebSearchConfig.keyName(source);
        String pending = keyName == null ? null : secretChanges.get(keyName);
        boolean keySaved = keyName != null && (pending != null ? !pending.isEmpty() : savedKeys.contains(keyName));
        WebSourceDialog dialog = new WebSourceDialog(scope, null, source, keySaved, this::draft, () -> proxyAuth.getValue(), (changed, key) -> {
            int index = source == null ? -1 : sources.indexOf(source);
            if (index < 0) {
                sources.add(changed);
            } else {
                sources.set(index, changed);
            }
            if (key != null) {
                secretChanges.put(WebSearchConfig.keyName(changed), key);
            }
            grid.setItems(sources);
        });
        dialog.create();
        dialog.open();
    }

    private WebSearchConfig draft() {
        return new WebSearchConfig(sources, StringUtils.trimToNull(proxy.getValue()), value(timeout), value(maxResults),
                StringUtils.trimToNull(language.getValue()), safeSearch.getValue(), StringUtils.trimToNull(userAgent.getValue()),
                localOnly.getValue(), value(maxSearches), value(maxPages));
    }

    private PageConfig draftPages() {
        return new PageConfig(pageType.getValue(), StringUtils.trimToNull(pageUrl.getValue()), pageLocal.getValue(),
                StringUtils.trimToNull(pageProxy.getValue()), value(pageTimeout), value(pageMaxChars), pageAllowPrivate.getValue());
    }

    private static int value(IntegerField field) {
        return field.getValue() == null ? 0 : field.getValue();
    }

    private void save() {
        try {
            Map<String, String> secrets = new LinkedHashMap<>(secretChanges);
            if (!proxyAuth.isEmpty()) {
                secrets.put(WebSearchConfig.PROXY_SECRET, proxyAuth.getValue());
            } else if (StringUtils.isBlank(proxy.getValue()) && proxyAuthSaved) {
                secrets.put(WebSearchConfig.PROXY_SECRET, "");
            }
            settings.saveSearch(scope, null, draft(), secrets);

            PageSourceType type = pageType.getValue();
            Map<String, String> pageSecrets = new LinkedHashMap<>();
            if (type == PageSourceType.DIRECT) {
                pageSecrets.put(PageConfig.TOKEN_SECRET, "");
                if (!pageProxyAuth.isEmpty()) {
                    pageSecrets.put(WebSearchConfig.PROXY_SECRET, pageProxyAuth.getValue());
                } else if (StringUtils.isBlank(pageProxy.getValue())) {
                    pageSecrets.put(WebSearchConfig.PROXY_SECRET, "");
                }
            } else {
                pageSecrets.put(WebSearchConfig.PROXY_SECRET, "");
                if (!pageToken.isEmpty()) {
                    pageSecrets.put(PageConfig.TOKEN_SECRET, pageToken.getValue());
                }
            }
            settings.savePages(scope, null, draftPages(), pageSecrets);
            load();
            Notification.success(loc.getValue(J_L.MSG_SETTINGS_SAVED));
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void clear() {
        try {
            settings.clearSearch(scope, null);
            settings.clearPages(scope, null);
            load();
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    private void testPage() {
        String url = testUrl.getValue().strip();
        if (url.isEmpty()) {
            return;
        }
        long started = System.nanoTime();
        try {
            PageSourceType type = pageType.getValue();
            String typedToken = type != PageSourceType.DIRECT && !pageToken.isEmpty() ? pageToken.getValue() : null;
            String typedProxy = type == PageSourceType.DIRECT && !pageProxyAuth.isEmpty() ? pageProxyAuth.getValue() : null;
            String text = settings.testPage(scope, null, draftPages(), typedToken, typedProxy, url);
            long ms = (System.nanoTime() - started) / 1_000_000;
            testResult.setText(String.format(loc.getValue(J_L.MSG_TEST_PAGE_OK), text.length(), ms,
                    StringUtils.abbreviate(StringUtils.normalizeSpace(text), 140)));
            testResult.removeClassName(CssStyles.TEXT_ERROR);
            testResult.addClassName(CssStyles.TEXT_SUCCESS);
        } catch (WebException e) {
            testResult.setText(String.format(loc.getValue(J_L.MSG_TEST_FAILED), e.getMessage()));
            testResult.removeClassName(CssStyles.TEXT_SUCCESS);
            testResult.addClassName(CssStyles.TEXT_ERROR);
        } catch (Exception e) {
            UIUtils.internalServerError(loc, e);
        }
    }

    // for tests

    public List<SearchSource> getSources() {
        return sources;
    }

    public Grid<SearchSource> getGrid() {
        return grid;
    }

    public TextField getProxy() {
        return proxy;
    }

    public IntegerField getMaxSearches() {
        return maxSearches;
    }

    public Select<PageSourceType> getPageType() {
        return pageType;
    }

    public TextField getPageUrl() {
        return pageUrl;
    }

    /** Stores what the page shows. */
    public void saveNow() {
        save();
    }
}
