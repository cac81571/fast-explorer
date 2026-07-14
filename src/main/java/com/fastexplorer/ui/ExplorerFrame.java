package com.fastexplorer.ui;

import com.fastexplorer.config.AppSettingsStore;
import com.fastexplorer.config.BookmarkStore;
import com.fastexplorer.config.PathHistoryStore;
import com.fastexplorer.fs.CachedFileSystemService;
import com.fastexplorer.model.FileEntry;
import com.fastexplorer.model.GrepMatch;
import com.fastexplorer.model.GrepOptions;
import com.fastexplorer.model.GrepResult;
import com.fastexplorer.model.ListDirectoryResult;
import com.fastexplorer.model.SearchOptions;
import com.fastexplorer.model.SearchResult;
import com.fastexplorer.model.TaskProgress;
import com.fastexplorer.util.ExternalEditorOpener;
import com.fastexplorer.util.FileTypeUtil;
import com.fastexplorer.util.LocalFileOpener;
import com.fastexplorer.util.PathUtil;
import com.fastexplorer.util.PowerShellCopyScriptBuilder;
import com.fastexplorer.util.SearchMatcher;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ItemEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class ExplorerFrame extends JFrame {

    private static final Color FOLDER_COLOR = new Color(28, 100, 171);
    private static final Color TEXT_FILE_COLOR = new Color(0, 120, 70);
    private static final Color BINARY_FILE_COLOR = new Color(140, 75, 30);
    private static final Color GREP_HIT_COLOR = new Color(180, 30, 30);

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault());

    private static final int MAX_PATH_HISTORY = PathHistoryStore.MAX_ENTRIES;

    private final CachedFileSystemService fs = new CachedFileSystemService();
    private final FileTableModel tableModel = new FileTableModel();
    private TableRowSorter<TableModel> tableSorter;
    private final JTable table = new JTable(tableModel);
    private final JTabbedPane resultTabs = new JTabbedPane();
    private final JScrollPane mainTableScrollPane = new JScrollPane(table);
    private final HistoryTextField grepField = new HistoryTextField("grep.pattern");
    private final HistoryTextField grepPathField = new HistoryTextField("grep.path");
    private final HistoryTextField grepFileNameField = new HistoryTextField("grep.file");
    private final HistoryTextField grepExtensionField = new HistoryTextField("grep.extension");
    private final HistoryTextField grepEditorField = new HistoryTextField("grep.editor", true);
    private final JCheckBox grepRegexCheck = new JCheckBox("正規表現", false);
    private final JCheckBox grepRecursiveCheck = new JCheckBox("サブフォルダ", true);
    private final JSpinner grepContextSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 20, 1));
    private final JButton grepBtn = new JButton("Grep");
    private final JButton saveGrepFavoriteBtn = new JButton("★");
    private final JComboBox<String> pathCombo = new JComboBox<>();
    private final HistoryTextField searchPathField = new HistoryTextField("search.path");
    private final HistoryTextField searchFileNameField = new HistoryTextField("search.file");
    private final HistoryTextField searchExtensionField = new HistoryTextField("search.extension");
    private final JButton searchBtn = new JButton("検索");
    private final JButton indexBtn = new JButton("インデックス");
    private final JButton saveSearchFavoriteBtn = new JButton("★");
    private final JLabel statusLabel = new JLabel(" ");
    private final JProgressBar taskProgressBar = new JProgressBar();
    private final JCheckBox subfolderSearchCheck = new JCheckBox("サブフォルダも検索", true);
    private final JCheckBox directoriesOnlySearchCheck = new JCheckBox("フォルダのみ", false);
    private final JButton backBtn = new JButton("←");
    private final JButton forwardBtn = new JButton("→");
    private final JButton upBtn = new JButton("↑");
    private final JButton refreshBtn = new JButton("↻");
    private final JCheckBox fetchMetadataCheck = new JCheckBox("サイズ・更新日時", true);
    private static final String EXPLORER_TAB_TITLE = "エクスプローラ";
    private static final String SETTINGS_TAB_TITLE = "設定";
    private static final int EXPLORER_TAB_INDEX = 0;
    private static final int SETTINGS_TAB_INDEX = 1;
    private static final int FIRST_DYNAMIC_TAB_INDEX = 2;
    private static final String GREP_TAB_PREFIX = "Grep結果";
    private static final String FILE_LIST_TAB_PREFIX = "ファイルリスト";
    private static final String FILE_LIST_NEW_TAB = "新規タブ";
    private static final String SCRIPT_TAB_TITLE = "スクリプト";
    private enum ActiveTask {
        NONE,
        DIRECTORY,
        SEARCH,
        INDEX,
        GREP
    }

    private final JComboBox<String> targetTabCombo = new JComboBox<>();
    private final JButton addResultTabBtn = new JButton("ファイルリスト追加");
    private final HistoryTextField copyBaseField = new HistoryTextField("filelist.copy.base");
    private final HistoryTextField copyDestField = new HistoryTextField("filelist.copy.dest");
    private final JButton generateCopyScriptBtn = new JButton("コピーコマンド(PS)生成");
    private int nextGrepResultNumber = 1;
    private int nextFileListNumber = 1;

    private final List<Path> history = new ArrayList<>();
    private int historyIndex = -1;
    private Path currentPath;
    private boolean loading;
    private ActiveTask activeTask = ActiveTask.NONE;
    private String taskStatusText = "";
    private long taskStartedAtMs;
    private int totalEntryCount;
    private SwingWorker<?, ?> activeWorker;
    private final AtomicBoolean searchCancel = new AtomicBoolean();
    private boolean updatingPathCombo;

    private final FavoritesPanel favoritesPanel = new FavoritesPanel(
            this::navigateToPath,
            this::persistBookmarks,
            this::getCurrentPathText,
            this::getCurrentSearchPreset,
            this::applySearchPreset,
            this::runSubfolderSearch,
            this::getCurrentGrepPreset,
            this::applyGrepPreset,
            this::runGrep
    );

    private final List<HistoryTextField> historyFields = List.of(
            grepField,
            grepPathField,
            grepFileNameField,
            grepExtensionField,
            grepEditorField,
            searchPathField,
            searchFileNameField,
            searchExtensionField,
            copyBaseField,
            copyDestField
    );

    public ExplorerFrame() {
        super("Fast Explorer");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setMinimumSize(new Dimension(800, 500));
        setLocationRelativeTo(null);

        initTable();
        initToolbar();
        initLayout();
        applySettings(AppSettingsStore.load());
        favoritesPanel.loadBookmarks(BookmarkStore.load());
        installWindowListeners();
        restorePathHistoryAndNavigate();
    }

    private void installWindowListeners() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                persistPathHistory();
                persistInputHistory();
                persistBookmarks();
                persistSettings();
                fs.shutdown();
            }
        });
    }

    private void restorePathHistoryAndNavigate() {
        PathHistoryStore.State saved = PathHistoryStore.load();

        updatingPathCombo = true;
        try {
            DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) pathCombo.getModel();
            for (String path : saved.history()) {
                model.addElement(path);
            }
            if (!saved.lastPath().isBlank()) {
                setPathComboText(saved.lastPath());
            }
        } finally {
            updatingPathCombo = false;
        }

        Path startupPath = resolveStartupPath(saved.lastPath());
        if (saved.lastPath().isBlank()) {
            setPathComboText(PathUtil.toDisplay(startupPath));
        }
        loadDirectory(startupPath, true, false);
    }

    private Path resolveStartupPath(String lastPath) {
        Path fallback = Paths.get(System.getProperty("user.home"));
        if (lastPath == null || lastPath.isBlank()) {
            return fallback;
        }
        try {
            Path parsed = PathUtil.parse(lastPath);
            Path resolved = PathUtil.resolveForAccess(parsed);
            if (Files.isDirectory(resolved)) {
                return parsed;
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return fallback;
    }

    private void initTable() {
        table.setRowHeight(28);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tableSorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(tableSorter);
        table.getTableHeader().setReorderingAllowed(false);
        configureTableColumns();
        installFileEntryRenderer();
        initContextMenu();

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    FileEntry entry = getEntryAtPoint(e.getPoint());
                    if (entry != null) {
                        openEntry(entry);
                    }
                }
            }
        });
    }

    private void initContextMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem openServerFileItem = new JMenuItem("サーバのファイルを開く");
        openServerFileItem.addActionListener(e -> {
            FileEntry entry = getSelectedEntry();
            if (entry != null && !entry.directory()) {
                openFileOnServer(entry.path());
            }
        });
        JMenuItem copyPathItem = new JMenuItem("パスのコピー");
        copyPathItem.addActionListener(e -> {
            FileEntry entry = getSelectedEntry();
            if (entry != null) {
                copyToClipboard(entry.path().toString());
            }
        });
        popup.add(openServerFileItem);
        popup.add(copyPathItem);

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showPopup(e);
            }

            private void showPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int viewRow = table.rowAtPoint(e.getPoint());
                if (viewRow < 0) {
                    return;
                }
                table.setRowSelectionInterval(viewRow, viewRow);
                FileEntry entry = getSelectedEntry();
                openServerFileItem.setVisible(entry != null
                        && !entry.directory()
                        && PathUtil.isUnc(entry.path()));
                popup.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    private FileEntry getEntryAtPoint(Point point) {
        int viewRow = table.rowAtPoint(point);
        if (viewRow < 0) {
            return null;
        }
        return tableModel.getEntry(table.convertRowIndexToModel(viewRow));
    }

    private FileEntry getSelectedEntry() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        return tableModel.getEntry(table.convertRowIndexToModel(viewRow));
    }

    private void copyToClipboard(String text) {
        StringSelection selection = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
        statusLabel.setText("コピーしました: " + text);
    }

    private void installFileEntryRenderer() {
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                if (t.getModel() == tableModel) {
                    int modelRow = t.convertRowIndexToModel(row);
                    FileEntry entry = tableModel.getEntry(modelRow);
                    if (entry == null) {
                        return c;
                    }
                    if (isSelected) {
                        c.setForeground(t.getSelectionForeground());
                        c.setBackground(t.getSelectionBackground());
                    } else if (entry.directory()) {
                        c.setForeground(FOLDER_COLOR);
                        c.setBackground(t.getBackground());
                    } else if (FileTypeUtil.isTextFile(entry.name())) {
                        c.setForeground(TEXT_FILE_COLOR);
                        c.setBackground(t.getBackground());
                    } else {
                        c.setForeground(BINARY_FILE_COLOR);
                        c.setBackground(t.getBackground());
                    }
                }
                return c;
            }
        });
    }

    private void configureTableColumns() {
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(100);
        DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
        rightRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(2).setCellRenderer(rightRenderer);
        table.getColumnModel().getColumn(3).setPreferredWidth(160);
    }

    private void initToolbar() {
        backBtn.setToolTipText("戻る");
        forwardBtn.setToolTipText("進む");
        upBtn.setToolTipText("上のフォルダ");
        refreshBtn.setToolTipText("更新");
        fetchMetadataCheck.setToolTipText("OFF にするとサイズ・更新日時の取得を省略し、一覧・検索を高速化できます");
        searchPathField.setToolTipText("相対パス（* ? で glob、カンマ区切り。ワイルドカードなしは部分一致）▼ で履歴");
        searchFileNameField.setToolTipText("ファイル名（* ? で glob、カンマ区切り。ワイルドカードなしは部分一致）▼ で履歴");
        searchExtensionField.setToolTipText("拡張子（カンマ区切り、例: .java,.xml または java,log）▼ で履歴");
        subfolderSearchCheck.setToolTipText("ON のとき検索ボタン / Enter で現在のフォルダ以下を再帰検索");
        directoriesOnlySearchCheck.setToolTipText("ON のときフォルダだけを検索対象にする（ファイルは除外）");
        indexBtn.setToolTipText("現在のフォルダ以下を検索用に事前インデックス（有効期限 7 日、作り直しは再実行）");
        addResultTabBtn.setToolTipText("Grep結果のファイルパスをファイルリストタブへ追加 / ファイルリストタブをマージ");
        copyBaseField.setToolTipText("このフォルダ以降の階層を維持してコピー（空=現在のパス）▼ で履歴");
        copyDestField.setToolTipText("コピー先フォルダ（相対/絶対/UNC 可）▼ で履歴");
        generateCopyScriptBtn.setToolTipText("PowerShell の mkdir / Copy-Item スクリプトを生成（未選択時は全ファイル）");
        targetTabCombo.setToolTipText("追加先（新規タブ / ファイルリスト …）");
        pathCombo.setEditable(true);
        pathCombo.setToolTipText("パスを入力するか、履歴から選択");
        grepField.setToolTipText("本文検索（空だとパス/ファイル名/拡張子に合うファイル一覧）▼ で履歴");
        grepPathField.setToolTipText("検索開始パス（空=現在のフォルダ、相対/絶対/UNC 可）▼ で履歴");
        grepFileNameField.setToolTipText("対象ファイル名（ワイルドカード: * ? 例: *.java, test?.txt）▼ で履歴");
        grepExtensionField.setToolTipText("拡張子（空=テキスト系すべて、例: .java,.xml または java,log）▼ で履歴");
        grepEditorField.setToolTipText("Grep結果ダブルクリック時に実行するコマンド（例: code -g {file}:{line}）▼ で履歴");
        grepRegexCheck.setToolTipText("正規表現として解釈");
        grepRecursiveCheck.setToolTipText("サブフォルダ内も検索（フォルダ Grep / ファイルリスト内のフォルダ）");
        grepContextSpinner.setToolTipText("ヒット行の前後に表示する行数（0=ヒット行のみ）");

        backBtn.addActionListener(e -> goBack());
        forwardBtn.addActionListener(e -> goForward());
        upBtn.addActionListener(e -> goUp());
        refreshBtn.addActionListener(e -> {
            if (currentPath != null) {
                loadDirectory(currentPath, false, true);
            }
        });

        fetchMetadataCheck.addActionListener(e -> onFetchMetadataChanged());

        pathCombo.addItemListener(e -> {
            if (e.getStateChange() != ItemEvent.SELECTED || updatingPathCombo) {
                return;
            }
            Object item = e.getItem();
            if (item != null) {
                navigateToPath(item.toString());
            }
        });

        Component pathEditor = pathCombo.getEditor().getEditorComponent();
        if (pathEditor instanceof JTextField editorField) {
            editorField.addActionListener(e -> navigateToPath(getPathInput()));
        }

        installClearSearchShortcut(searchPathField);
        installClearSearchShortcut(searchFileNameField);
        installClearSearchShortcut(searchExtensionField);

        java.awt.event.ActionListener searchEnterAction = e -> {
            commitSearchFieldHistory();
            if (subfolderSearchCheck.isSelected()) {
                runSubfolderSearch();
            }
        };
        searchPathField.addEditorActionListener(searchEnterAction);
        searchFileNameField.addEditorActionListener(searchEnterAction);
        searchExtensionField.addEditorActionListener(searchEnterAction);

        DocumentListener filterListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilters();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilters();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilters();
            }
        };
        searchPathField.getDocument().addDocumentListener(filterListener);
        searchFileNameField.getDocument().addDocumentListener(filterListener);
        searchExtensionField.getDocument().addDocumentListener(filterListener);

        subfolderSearchCheck.addActionListener(e -> applyFilters());
        directoriesOnlySearchCheck.addActionListener(e -> {
            updateBusyControls();
            applyFilters();
        });

        addResultTabBtn.addActionListener(e -> addOrMergeFileListTab());
        generateCopyScriptBtn.addActionListener(e -> generateCopyScript());

        grepField.addEditorActionListener(e -> runGrep());

        resultTabs.addChangeListener(e -> onResultTabSelectionChanged());
    }

    private void initLayout() {
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));

        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));

        JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        nav.add(backBtn);
        nav.add(forwardBtn);
        nav.add(upBtn);
        nav.add(refreshBtn);
        nav.add(fetchMetadataCheck);

        JPanel pathPanel = new JPanel(new BorderLayout(6, 0));
        pathPanel.add(pathCombo, BorderLayout.CENTER);
        JPanel pathActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton addFavoriteBtn = new JButton("★");
        addFavoriteBtn.setToolTipText("現在のパスをお気に入りに追加");
        addFavoriteBtn.addActionListener(e -> addCurrentPathToFavorites());
        JButton goBtn = new JButton("移動");
        goBtn.addActionListener(e -> navigateToPath(getPathInput()));
        pathActions.add(addFavoriteBtn);
        pathActions.add(goBtn);
        pathPanel.add(pathActions, BorderLayout.EAST);

        toolbar.add(nav, BorderLayout.WEST);
        toolbar.add(pathPanel, BorderLayout.CENTER);

        JPanel searchBar = new JPanel(new BorderLayout(8, 0));
        searchBar.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));

        JPanel searchFields = buildLabelFieldRow(
                "パス", searchPathField,
                "ファイル", searchFileNameField,
                "拡張子", searchExtensionField
        );
        JPanel searchActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        searchActions.add(directoriesOnlySearchCheck);
        searchActions.add(subfolderSearchCheck);
        saveSearchFavoriteBtn.setToolTipText("現在の検索条件をお気に入りに追加");
        saveSearchFavoriteBtn.addActionListener(e -> favoritesPanel.addCurrentSearch());
        searchBtn.addActionListener(e -> {
            if (activeTask == ActiveTask.SEARCH) {
                requestCancelActiveTask("検索");
                return;
            }
            runSubfolderSearch();
        });
        indexBtn.addActionListener(e -> {
            if (activeTask == ActiveTask.INDEX) {
                requestCancelActiveTask("インデックス");
                return;
            }
            runTreeIndex(true);
        });
        searchActions.add(saveSearchFavoriteBtn);
        searchActions.add(indexBtn);
        searchActions.add(searchBtn);

        JPanel searchRow = new JPanel(new BorderLayout(8, 0));
        searchRow.add(searchFields, BorderLayout.CENTER);
        searchRow.add(searchActions, BorderLayout.EAST);
        searchBar.add(searchRow, BorderLayout.CENTER);

        north.add(toolbar);
        north.add(searchBar);

        JPanel grepBar = new JPanel();
        grepBar.setLayout(new BoxLayout(grepBar, BoxLayout.Y_AXIS));
        grepBar.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));

        JPanel grepRow1 = new JPanel(new BorderLayout(6, 0));
        grepRow1.add(new JLabel("Grep"), BorderLayout.WEST);
        grepRow1.add(grepField, BorderLayout.CENTER);
        JPanel grepActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        grepActions.add(grepRegexCheck);
        grepActions.add(grepRecursiveCheck);
        grepActions.add(new JLabel("前後"));
        grepActions.add(grepContextSpinner);
        grepActions.add(new JLabel("行"));
        grepBtn.addActionListener(e -> {
            if (activeTask == ActiveTask.GREP) {
                requestCancelActiveTask("Grep");
                return;
            }
            runGrep();
        });
        saveGrepFavoriteBtn.setToolTipText("現在の Grep 条件をお気に入りに追加");
        saveGrepFavoriteBtn.addActionListener(e -> favoritesPanel.addCurrentGrep());
        grepActions.add(saveGrepFavoriteBtn);
        grepActions.add(grepBtn);
        grepRow1.add(grepActions, BorderLayout.EAST);

        JPanel grepRow2 = buildLabelFieldRow(
                "パス", grepPathField,
                "ファイル", grepFileNameField,
                "拡張子", grepExtensionField
        );

        JPanel grepRow3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        grepRow3.add(new JLabel("追加先"));
        grepRow3.add(targetTabCombo);
        grepRow3.add(addResultTabBtn);

        grepBar.add(grepRow1);
        grepBar.add(Box.createVerticalStrut(4));
        grepBar.add(grepRow2);
        grepBar.add(Box.createVerticalStrut(4));
        grepBar.add(grepRow3);
        grepBar.add(Box.createVerticalStrut(4));

        JPanel grepRow4 = new JPanel(new BorderLayout(8, 0));
        JPanel copyFields = buildLabelFieldRow(
                "コピー元ベース", copyBaseField,
                "コピー先", copyDestField
        );
        grepRow4.add(copyFields, BorderLayout.CENTER);
        grepRow4.add(generateCopyScriptBtn, BorderLayout.EAST);
        grepBar.add(grepRow4);
        north.add(grepBar);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        taskProgressBar.setIndeterminate(true);
        taskProgressBar.setVisible(false);
        taskProgressBar.setPreferredSize(new Dimension(120, taskProgressBar.getPreferredSize().height));

        JPanel statusPanel = new JPanel(new BorderLayout(8, 0));
        statusPanel.add(statusLabel, BorderLayout.CENTER);
        statusPanel.add(taskProgressBar, BorderLayout.EAST);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(north, BorderLayout.NORTH);
        resultTabs.addTab(EXPLORER_TAB_TITLE, mainTableScrollPane);
        resultTabs.addTab(SETTINGS_TAB_TITLE, buildSettingsPanel());
        installResultTabContextMenu();
        updateTargetTabCombo();
        onResultTabSelectionChanged();
        JSplitPane centerSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                favoritesPanel,
                resultTabs
        );
        centerSplit.setDividerLocation(220);
        centerSplit.setOneTouchExpandable(true);
        centerSplit.setResizeWeight(0);
        centerSplit.setContinuousLayout(true);
        getContentPane().add(centerSplit, BorderLayout.CENTER);
        getContentPane().add(statusPanel, BorderLayout.SOUTH);
    }

    private void beginTask(ActiveTask task, String statusText) {
        activeTask = task;
        loading = true;
        taskStatusText = statusText;
        taskStartedAtMs = System.currentTimeMillis();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        statusLabel.setText(statusText);
        taskProgressBar.setVisible(
                task == ActiveTask.SEARCH
                        || task == ActiveTask.INDEX
                        || task == ActiveTask.GREP);
        taskProgressBar.setIndeterminate(true);
        taskProgressBar.setValue(0);
        taskProgressBar.setMaximum(100);
        updateBusyControls();
    }

    private void updateTaskStatus(String statusText) {
        taskStatusText = statusText;
        if (loading) {
            statusLabel.setText(statusText);
        }
    }

    private void updateTaskProgress(TaskProgress progress) {
        taskStatusText = progress.formatStatus();
        if (loading) {
            statusLabel.setText(taskStatusText);
        }
        if (progress.hasTotal()) {
            taskProgressBar.setIndeterminate(false);
            taskProgressBar.setMaximum(progress.total());
            taskProgressBar.setValue(Math.min(progress.processed(), progress.total()));
        } else if (progress.processed() > 0) {
            taskProgressBar.setIndeterminate(true);
        }
    }

    private void endTask() {
        activeTask = ActiveTask.NONE;
        loading = false;
        taskStatusText = "";
        setCursor(Cursor.getDefaultCursor());
        taskProgressBar.setVisible(false);
        updateBusyControls();
    }

    private void updateBusyControls() {
        boolean busy = loading;
        boolean searching = activeTask == ActiveTask.SEARCH;
        boolean indexing = activeTask == ActiveTask.INDEX;
        boolean grepping = activeTask == ActiveTask.GREP;

        ResultTabPanel selected = getSelectedResultTab();
        boolean grepTabSelected = selected != null && selected.kind() == ResultTabKind.GREP_RESULT;
        boolean fileListTabSelected = selected != null && selected.kind() == ResultTabKind.FILE_LIST;
        boolean resultTabSelected = grepTabSelected || fileListTabSelected;
        boolean searchControlsEnabled = !busy && !resultTabSelected;

        searchBtn.setText(searching ? "検索キャンセル" : "検索");
        searchBtn.setEnabled(searchControlsEnabled || searching);
        searchBtn.setToolTipText(resultTabSelected && !searching
                ? "結果タブでは検索できません（ファイルリストの絞り込みは未対応。Grep を使ってください）"
                : "現在のフォルダ以下を検索");
        indexBtn.setText(indexing ? "インデックスキャンセル" : "インデックス");
        indexBtn.setEnabled((!busy && !resultTabSelected) || indexing);
        searchPathField.setEnabled(searchControlsEnabled);
        searchFileNameField.setEnabled(searchControlsEnabled);
        searchExtensionField.setEnabled(searchControlsEnabled && !directoriesOnlySearchCheck.isSelected());
        subfolderSearchCheck.setEnabled(searchControlsEnabled);
        directoriesOnlySearchCheck.setEnabled(searchControlsEnabled);
        saveSearchFavoriteBtn.setEnabled(searchControlsEnabled);

        boolean grepControlsEnabled = !busy && !grepTabSelected;

        grepBtn.setText(grepping ? "Grepキャンセル" : "Grep");
        grepBtn.setEnabled(grepControlsEnabled || grepping);
        grepField.setEnabled(grepControlsEnabled);
        grepPathField.setEnabled(grepControlsEnabled);
        grepPathField.setToolTipText(fileListTabSelected
                ? "パスの絞り込み（* ? で glob、カンマ区切り。ワイルドカードなしは部分一致）▼ で履歴"
                : "検索開始パス（空=現在のフォルダ、相対/絶対/UNC 可）▼ で履歴");
        grepFileNameField.setEnabled(grepControlsEnabled);
        grepExtensionField.setEnabled(grepControlsEnabled);
        grepRegexCheck.setEnabled(grepControlsEnabled);
        grepRecursiveCheck.setEnabled(grepControlsEnabled);
        grepContextSpinner.setEnabled(grepControlsEnabled);
        saveGrepFavoriteBtn.setEnabled(!busy);

        boolean fileListControlsEnabled = !busy && (grepTabSelected || fileListTabSelected);
        addResultTabBtn.setEnabled(fileListControlsEnabled);
        targetTabCombo.setEnabled(fileListControlsEnabled);

        boolean fileListCopyEnabled = fileListTabSelected && !busy;
        copyBaseField.setEnabled(fileListTabSelected && !busy);
        copyDestField.setEnabled(fileListTabSelected && !busy);
        generateCopyScriptBtn.setEnabled(fileListCopyEnabled);

        backBtn.setEnabled(!busy && historyIndex > 0);
        forwardBtn.setEnabled(!busy && historyIndex >= 0 && historyIndex < history.size() - 1);
        upBtn.setEnabled(!busy && currentPath != null && currentPath.getParent() != null);
        refreshBtn.setEnabled(!busy && currentPath != null);
        pathCombo.setEnabled(!busy);
    }

    private String getCurrentPathText() {
        if (currentPath == null) {
            return "";
        }
        return PathUtil.toDisplay(currentPath);
    }

    private void addCurrentPathToFavorites() {
        String path = getCurrentPathText();
        if (path.isBlank()) {
            showError("現在のパスがありません");
            return;
        }
        favoritesPanel.addCurrentPath(path);
    }

    private void persistBookmarks() {
        BookmarkStore.save(favoritesPanel.getRootChildren());
    }

    private BookmarkSearchPreset getCurrentSearchPreset() {
        return new BookmarkSearchPreset(
                searchPathField.getText().trim(),
                searchFileNameField.getText().trim(),
                searchExtensionField.getText().trim(),
                directoriesOnlySearchCheck.isSelected()
        );
    }

    private void applySearchPreset(BookmarkSearchPreset preset) {
        searchPathField.setText(preset.pathPatterns());
        searchFileNameField.setText(preset.filePatterns());
        searchExtensionField.setText(preset.extensions());
        directoriesOnlySearchCheck.setSelected(preset.directoriesOnly());
        updateBusyControls();
    }

    private BookmarkGrepPreset getCurrentGrepPreset() {
        return new BookmarkGrepPreset(
                grepField.getText().trim(),
                grepPathField.getText().trim(),
                grepFileNameField.getText().trim(),
                grepExtensionField.getText().trim(),
                grepRegexCheck.isSelected(),
                grepRecursiveCheck.isSelected(),
                ((Number) grepContextSpinner.getValue()).intValue()
        );
    }

    private void applyGrepPreset(BookmarkGrepPreset preset) {
        grepField.setText(preset.pattern());
        grepPathField.setText(preset.path());
        grepFileNameField.setText(preset.file());
        grepExtensionField.setText(preset.extension());
        grepRegexCheck.setSelected(preset.regex());
        grepRecursiveCheck.setSelected(preset.recursive());
        grepContextSpinner.setValue(preset.context());
    }

    private void applySettings(AppSettingsStore.Settings settings) {
        fetchMetadataCheck.setSelected(settings.fetchMetadata());
    }

    private void persistSettings() {
        AppSettingsStore.save(new AppSettingsStore.Settings(fetchMetadataCheck.isSelected()));
    }

    private boolean fetchMetadata() {
        return fetchMetadataCheck.isSelected();
    }

    private void onFetchMetadataChanged() {
        persistSettings();
        if (currentPath != null && !loading) {
            loadDirectory(currentPath, false, true);
        }
    }

    private JPanel buildSettingsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel editorRow = buildLabelFieldRow("エディタ", grepEditorField);

        JPanel metadataRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        metadataRow.add(new JLabel("サイズ・更新日時:"));
        metadataRow.add(new JLabel("ツールバーのチェックボックスで切り替え"));
        JTextArea metadataHelp = new JTextArea(
                "ON: ファイルのサイズと更新日時を取得して表示します。\n"
                        + "OFF: 取得を省略して一覧・検索を高速化します（列には「—」と表示）。"
        );
        metadataHelp.setEditable(false);
        metadataHelp.setFocusable(false);
        metadataHelp.setOpaque(false);
        metadataHelp.setBorder(null);
        metadataHelp.setFont(UIManager.getFont("Label.font"));
        metadataHelp.setForeground(UIManager.getColor("Label.disabledForeground"));

        JTextArea help = new JTextArea(
                "Grep結果をダブルクリックしたときに実行するコマンド。\n"
                        + "プレースホルダ: {file}, {line}, {column}, {file}:{line}\n"
                        + "例: code -g {file}:{line}\n"
                        + "     cursor -g {file}:{line}"
        );
        help.setEditable(false);
        help.setFocusable(false);
        help.setOpaque(false);
        help.setBorder(null);
        help.setFont(UIManager.getFont("Label.font"));
        help.setForeground(UIManager.getColor("Label.disabledForeground"));

        JTextArea indexHelp = new JTextArea(
                "検索バーの「インデックス」で現在フォルダ以下を事前登録できます。\n"
                        + "検索用インデックスの有効期限は 7 日です（フォルダ一覧キャッシュは従来どおり短め）。\n"
                        + "再作成したいときは、もう一度「インデックス」を実行してください。"
        );
        indexHelp.setEditable(false);
        indexHelp.setFocusable(false);
        indexHelp.setOpaque(false);
        indexHelp.setBorder(null);
        indexHelp.setFont(UIManager.getFont("Label.font"));
        indexHelp.setForeground(UIManager.getColor("Label.disabledForeground"));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(metadataRow);
        content.add(Box.createVerticalStrut(4));
        content.add(metadataHelp);
        content.add(Box.createVerticalStrut(12));
        content.add(indexHelp);
        content.add(Box.createVerticalStrut(12));
        content.add(editorRow);
        content.add(Box.createVerticalStrut(8));
        content.add(help);
        panel.add(content, BorderLayout.NORTH);
        return panel;
    }

    private static boolean isFixedTabIndex(int index) {
        return index == EXPLORER_TAB_INDEX || index == SETTINGS_TAB_INDEX;
    }

    private static boolean isFixedTabTitle(String title) {
        return EXPLORER_TAB_TITLE.equals(title) || SETTINGS_TAB_TITLE.equals(title);
    }

    private JPanel buildLabelFieldRow(Object... labelAndField) {
        JPanel row = new JPanel(new GridBagLayout());
        int pairCount = labelAndField.length / 2;
        JLabel[] labels = new JLabel[pairCount];
        int maxLabelWidth = 0;
        for (int i = 0; i < labelAndField.length; i += 2) {
            JLabel label = new JLabel(String.valueOf(labelAndField[i]));
            labels[i / 2] = label;
            maxLabelWidth = Math.max(maxLabelWidth, label.getPreferredSize().width);
        }
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < labelAndField.length; i += 2) {
            int pairIndex = i / 2;
            JLabel label = labels[pairIndex];
            Dimension labelSize = new Dimension(maxLabelWidth, label.getPreferredSize().height);
            label.setMinimumSize(labelSize);
            label.setPreferredSize(labelSize);

            gbc.gridx = pairIndex * 2;
            gbc.weightx = 0;
            gbc.weighty = 0;
            gbc.fill = GridBagConstraints.NONE;
            gbc.insets = new Insets(0, pairIndex == 0 ? 0 : 8, 0, 4);
            row.add(label, gbc);

            gbc.gridx = pairIndex * 2 + 1;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(0, 0, 0, 8);
            row.add((JComponent) labelAndField[i + 1], gbc);
        }
        return row;
    }

    private String getPathInput() {
        Object item = pathCombo.getEditor().getItem();
        return item != null ? item.toString().trim() : "";
    }

    private void setPathComboText(String path) {
        updatingPathCombo = true;
        try {
            pathCombo.getEditor().setItem(path);
            pathCombo.setSelectedItem(path);
        } finally {
            updatingPathCombo = false;
        }
    }

    private void addToPathHistory(String path) {
        if (path.isBlank()) {
            return;
        }
        updatingPathCombo = true;
        try {
            DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) pathCombo.getModel();
            for (int i = 0; i < model.getSize(); i++) {
                if (path.equals(model.getElementAt(i))) {
                    model.removeElementAt(i);
                    break;
                }
            }
            model.insertElementAt(path, 0);
            while (model.getSize() > MAX_PATH_HISTORY) {
                model.removeElementAt(model.getSize() - 1);
            }
            pathCombo.getEditor().setItem(path);
            pathCombo.setSelectedItem(path);
            persistPathHistory();
        } finally {
            updatingPathCombo = false;
        }
    }

    private List<String> getPathHistoryItems() {
        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) pathCombo.getModel();
        List<String> items = new ArrayList<>(model.getSize());
        for (int i = 0; i < model.getSize(); i++) {
            items.add(model.getElementAt(i));
        }
        return items;
    }

    private void persistPathHistory() {
        PathHistoryStore.save(getPathInput(), getPathHistoryItems());
    }

    private void navigateToPath(String text) {
        if (text.isBlank()) {
            return;
        }
        try {
            Path path = PathUtil.parse(text);
            if (PathUtil.isSame(path, currentPath)) {
                setPathComboText(PathUtil.toDisplay(path));
                showExplorerTab();
                return;
            }
            clearNavigationState();
            loadDirectory(path, true, false);
        } catch (Exception ex) {
            showError("パスが無効です: " + text);
        }
    }

    private void showExplorerTab() {
        if (resultTabs.getSelectedIndex() != 0) {
            resultTabs.setSelectedIndex(0);
        }
    }

    private void loadDirectory(Path path, boolean pushHistory, boolean forceRefresh) {
        final Path normalizedPath = path.toAbsolutePath().normalize();
        cancelActiveWorker();
        tableModel.setDisplayBase(null);
        beginTask(ActiveTask.DIRECTORY, forceRefresh ? "更新中…" : "読み込み中…");

        boolean includeMetadata = fetchMetadata();
        final Path requestedPath = normalizedPath;

        SwingWorker<ListDirectoryResult, Void> worker = new SwingWorker<>() {
            @Override
            protected ListDirectoryResult doInBackground() throws Exception {
                return fs.listDirectory(normalizedPath, includeMetadata, forceRefresh, fresh -> SwingUtilities.invokeLater(() -> {
                    if (PathUtil.isSame(fresh.path(), requestedPath)) {
                        applyDirectoryResult(fresh, false);
                    }
                }));
            }

            @Override
            protected void done() {
                finishActiveWorker(this, result -> applyDirectoryResult(result, pushHistory));
            }
        };
        activeWorker = worker;
        worker.execute();
    }

    private void applyDirectoryResult(ListDirectoryResult result, boolean pushHistory) {
        currentPath = result.path().toAbsolutePath().normalize();
        addToPathHistory(PathUtil.toDisplay(currentPath));
        totalEntryCount = result.entries().size();
        tableModel.setEntries(result.entries());
        configureTableColumns();
        applyFilters();
        if (buildSearchOptions().isEmpty()) {
            updateStatusBar(result.elapsedMs(), buildDirectoryMode(result));
        }
        updateNavButtons();

        if (pushHistory) {
            if (historyIndex < history.size() - 1) {
                history.subList(historyIndex + 1, history.size()).clear();
            }
            if (history.isEmpty() || !history.get(history.size() - 1).equals(result.path())) {
                history.add(result.path());
                historyIndex = history.size() - 1;
            }
        }
        updateNavButtons();
        showExplorerTab();
    }

    private static String buildDirectoryMode(ListDirectoryResult result) {
        if (result.backgroundRefresh()) {
            return "更新完了";
        }
        if (result.fromCache()) {
            return "H2 キャッシュ";
        }
        return "ネットワーク";
    }

    private void runSubfolderSearch() {
        runSubfolderSearch(false);
    }

    private void runSubfolderSearch(boolean forceRefresh) {
        if (currentPath == null || loading) {
            return;
        }
        ResultTabPanel selected = getSelectedResultTab();
        if (selected != null && (selected.kind() == ResultTabKind.FILE_LIST
                || selected.kind() == ResultTabKind.GREP_RESULT)) {
            statusLabel.setText("結果タブでは検索できません（Grep を使ってください）");
            return;
        }

        SearchOptions options = buildSearchOptions();
        if (options.isEmpty()) {
            return;
        }

        commitSearchFieldHistory();

        if (!subfolderSearchCheck.isSelected()) {
            applyFilters();
            return;
        }

        cancelActiveWorker();
        searchCancel.set(false);

        beginTask(ActiveTask.SEARCH, "検索中… " + formatSearchCriteria(options));

        final ResultTabPanel[] searchTab = new ResultTabPanel[1];
        final SearchOptions searchOptions = options;
        final boolean includeMetadata = fetchMetadata();
        SwingWorker<SearchResult, Void> worker = new SwingWorker<>() {
            @Override
            protected SearchResult doInBackground() throws Exception {
                return fs.searchRecursive(
                        currentPath,
                        searchOptions,
                        searchCancel,
                        includeMetadata,
                        forceRefresh);
            }

            @Override
            protected void done() {
                finishActiveWorker(this, result -> applySearchResult(result, searchTab));
            }
        };
        activeWorker = worker;
        worker.execute();
    }

    private void runTreeIndex(boolean forceRefresh) {
        if (currentPath == null || loading) {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(
                this,
                "現在のフォルダ以下を検索用にインデックスします。\n"
                        + "対象: " + PathUtil.toDisplay(currentPath) + "\n"
                        + (forceRefresh ? "既存のインデックスがある場合は作り直します。\n" : "")
                        + "ネットワークフォルダでは時間がかかることがあります。よろしいですか？",
                "インデックスの確認",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (confirm != JOptionPane.OK_OPTION) {
            return;
        }

        cancelActiveWorker();
        searchCancel.set(false);
        beginTask(ActiveTask.INDEX, "インデックス作成中… " + PathUtil.toDisplay(currentPath));

        final Path root = currentPath;
        SwingWorker<Integer, Void> worker = new SwingWorker<>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return fs.buildTreeIndex(root, forceRefresh, searchCancel);
            }

            @Override
            protected void done() {
                finishActiveWorker(this, count -> {
                    if (searchCancel.get()) {
                        statusLabel.setText("インデックスをキャンセルしました");
                        return;
                    }
                    statusLabel.setText("インデックス完了: " + String.format("%,d", count)
                            + " 件  |  有効期限 7 日  |  " + PathUtil.toDisplay(root));
                });
            }
        };
        activeWorker = worker;
        worker.execute();
    }

    private void applySearchResult(SearchResult result, ResultTabPanel[] tabHolder) {
        if (searchCancel.get()) {
            return;
        }
        List<FileEntry> entries = result.entries();
        if (entries.isEmpty()) {
            if (tabHolder[0] == null) {
                JOptionPane.showMessageDialog(
                        this,
                        "検索条件に一致するファイルがありません",
                        "検索",
                        JOptionPane.INFORMATION_MESSAGE
                );
                statusLabel.setText("検索結果 0 件");
            } else {
                tabHolder[0].setFileEntries(List.of(), null, false);
                String mode = result.fromCache() ? "H2 インデックス検索" : "サブフォルダ検索";
                statusLabel.setText(tabHolder[0].statusText() + "  |  " + result.elapsedMs() + " ms  |  " + mode);
                resultTabs.setSelectedComponent(tabHolder[0]);
                onResultTabSelectionChanged();
            }
            return;
        }

        if (tabHolder[0] == null) {
            tabHolder[0] = new ResultTabPanel(ResultTabKind.FILE_LIST);
            resultTabs.addTab(allocateFileListTitle(), tabHolder[0]);
            updateTargetTabCombo();
        }
        String mode = result.fromCache() ? "H2 インデックス検索" : "サブフォルダ検索";
        tabHolder[0].setFileEntries(entries, null, false);
        resultTabs.setSelectedComponent(tabHolder[0]);
        statusLabel.setText(tabHolder[0].statusText() + "  |  " + result.elapsedMs() + " ms  |  " + mode);
        onResultTabSelectionChanged();
    }

    private void applyFilters() {
        SearchOptions options = buildSearchOptions();
        if (subfolderSearchCheck.isSelected()) {
            tableSorter.setRowFilter(null);
            return;
        }
        if (options.isEmpty() || currentPath == null) {
            tableSorter.setRowFilter(null);
            updateStatusBar(null, "フォルダ一覧");
            return;
        }

        Path root = currentPath.toAbsolutePath().normalize();
        tableSorter.setRowFilter(new RowFilter<Object, Object>() {
            @Override
            public boolean include(Entry<?, ?> entry) {
                int row = (Integer) entry.getIdentifier();
                FileEntry fileEntry = tableModel.getEntry(row);
                if (fileEntry == null) {
                    return false;
                }
                return SearchMatcher.matches(
                        root,
                        fileEntry.path(),
                        fileEntry.name(),
                        fileEntry.directory(),
                        options
                );
            }
        });
        updateStatusBar(null, "絞り込み");
    }

    private SearchOptions buildSearchOptions() {
        boolean directoriesOnly = directoriesOnlySearchCheck.isSelected();
        return new SearchOptions(
                parseCommaList(searchPathField.getText()),
                parseCommaList(searchFileNameField.getText()),
                directoriesOnly ? List.of() : parseCommaList(searchExtensionField.getText()),
                directoriesOnly
        );
    }

    private static List<String> parseCommaList(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String formatSearchCriteria(SearchOptions options) {
        List<String> parts = new ArrayList<>();
        if (!options.pathPatterns().isEmpty()) {
            parts.add("パス=" + String.join("|", options.pathPatterns()));
        }
        if (!options.fileNamePatterns().isEmpty()) {
            parts.add("ファイル=" + String.join("|", options.fileNamePatterns()));
        }
        if (!options.extensions().isEmpty() && !options.directoriesOnly()) {
            parts.add("拡張子=" + String.join("|", options.extensions()));
        }
        if (options.directoriesOnly()) {
            parts.add("フォルダのみ");
        }
        return String.join(" ", parts);
    }

    private void persistInputHistory() {
        grepEditorField.commitHistory();
        for (HistoryTextField field : historyFields) {
            field.persistHistory();
        }
    }

    private void commitSearchFieldHistory() {
        searchPathField.commitHistory();
        searchFileNameField.commitHistory();
        searchExtensionField.commitHistory();
    }

    private void commitGrepFieldHistory() {
        grepField.commitHistory();
        grepPathField.commitHistory();
        grepFileNameField.commitHistory();
        grepExtensionField.commitHistory();
    }

    private void installClearSearchShortcut(HistoryTextField field) {
        JTextField editor = field.getEditorField();
        if (editor == null) {
            return;
        }
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearSearch");
        editor.getActionMap().put("clearSearch", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                clearSearch();
                if (currentPath != null) {
                    loadDirectory(currentPath, false, false);
                }
            }
        });
    }

    private void clearNavigationState() {
        searchCancel.set(true);
        searchPathField.setText("");
        searchFileNameField.setText("");
        searchExtensionField.setText("");
        directoriesOnlySearchCheck.setSelected(false);
        tableSorter.setRowFilter(null);
        tableModel.setDisplayBase(null);
    }

    private void clearSearch() {
        clearNavigationState();
        grepField.setText("");
        grepPathField.setText("");
        grepFileNameField.setText("");
        grepExtensionField.setText("");
    }

    private void addOrMergeFileListTab() {
        ResultTabPanel selected = getSelectedResultTab();
        if (selected == null) {
            return;
        }
        if (selected.kind() == ResultTabKind.GREP_RESULT) {
            addGrepFilesToFileListTab();
        } else if (selected.kind() == ResultTabKind.FILE_LIST) {
            mergeFileListTab();
        }
    }

    private void addGrepFilesToFileListTab() {
        ResultTabPanel grepTab = getSelectedGrepResultTab();
        if (grepTab == null) {
            statusLabel.setText("Grep結果タブを選択してください");
            return;
        }

        List<FileEntry> entries = pathsToFileEntries(grepTab.scopePaths());
        if (entries.isEmpty()) {
            statusLabel.setText("追加できるファイルがありません");
            return;
        }

        Object selected = targetTabCombo.getSelectedItem();
        String target = selected != null ? selected.toString() : FILE_LIST_NEW_TAB;
        if (FILE_LIST_NEW_TAB.equals(target)) {
            ResultTabPanel panel = new ResultTabPanel(ResultTabKind.FILE_LIST);
            panel.setFileEntries(entries, null, false);
            resultTabs.addTab(allocateFileListTitle(), panel);
            resultTabs.setSelectedComponent(panel);
        } else {
            int index = findTabIndexByTitle(target);
            if (index < 0) {
                statusLabel.setText("追加先タブが見つかりません");
                return;
            }
            ResultTabPanel panel = getResultTabAt(index);
            if (panel == null || panel.kind() != ResultTabKind.FILE_LIST) {
                statusLabel.setText("ファイルリストタブを選択してください");
                return;
            }
            panel.setFileEntries(entries, null, true);
            resultTabs.setSelectedIndex(index);
        }
        updateTargetTabCombo();
        onResultTabSelectionChanged();
    }

    private void mergeFileListTab() {
        int sourceIndex = resultTabs.getSelectedIndex();
        ResultTabPanel sourceTab = getSelectedFileListTab();
        if (sourceTab == null) {
            statusLabel.setText("ファイルリストタブを選択してください");
            return;
        }

        List<FileEntry> entries = sourceTab.fileEntries();
        if (entries.isEmpty()) {
            statusLabel.setText("マージできるファイルがありません");
            return;
        }

        Object selected = targetTabCombo.getSelectedItem();
        String target = selected != null ? selected.toString() : FILE_LIST_NEW_TAB;
        if (FILE_LIST_NEW_TAB.equals(target)) {
            ResultTabPanel panel = new ResultTabPanel(ResultTabKind.FILE_LIST);
            panel.setFileEntries(entries, null, false);
            resultTabs.addTab(allocateFileListTitle(), panel);
            resultTabs.setSelectedComponent(panel);
        } else {
            int targetIndex = findTabIndexByTitle(target);
            if (targetIndex < 0) {
                statusLabel.setText("追加先タブが見つかりません");
                return;
            }
            ResultTabPanel targetPanel = getResultTabAt(targetIndex);
            if (targetPanel == null || targetPanel.kind() != ResultTabKind.FILE_LIST) {
                statusLabel.setText("追加先タブが見つかりません");
                return;
            }
            targetPanel.setFileEntries(entries, null, true);
            resultTabs.removeTabAt(sourceIndex);
            targetIndex = findTabIndexByTitle(target);
            if (targetIndex >= 0) {
                resultTabs.setSelectedIndex(targetIndex);
            }
        }
        updateTargetTabCombo();
        onResultTabSelectionChanged();
    }

    private void generateCopyScript() {
        if (loading) {
            return;
        }
        ResultTabPanel fileListTab = getSelectedFileListTab();
        if (fileListTab == null) {
            statusLabel.setText("ファイルリストタブを選択してください");
            return;
        }

        List<FileEntry> entries = fileListTab.selectedFileEntriesForCopy();
        if (entries.isEmpty()) {
            statusLabel.setText("コピーするファイルがありません");
            return;
        }

        String destText = copyDestField.getText().trim();
        if (destText.isEmpty()) {
            statusLabel.setText("コピー先フォルダを入力してください");
            return;
        }

        String baseText = copyBaseField.getText().trim();
        if (baseText.isEmpty() && currentPath != null) {
            baseText = PathUtil.toDisplay(currentPath);
            copyBaseField.setText(baseText);
        }
        if (baseText.isEmpty()) {
            statusLabel.setText("コピー元ベースフォルダを入力してください");
            return;
        }

        final Path destDir;
        final Path hierarchyBase;
        try {
            destDir = resolveFolderPath(destText);
            hierarchyBase = resolveFolderPath(baseText);
            if (!allFilesUnderBase(entries, hierarchyBase)) {
                showError("コピー元ベース配下にないファイルが含まれています: " + PathUtil.toDisplay(hierarchyBase));
                return;
            }
        } catch (IOException ex) {
            showError(formatError(ex));
            return;
        }

        copyBaseField.commitHistory();
        copyDestField.commitHistory();

        String script = PowerShellCopyScriptBuilder.build(entries, destDir, hierarchyBase);
        showCopyScript(script);
    }

    private void showCopyScript(String script) {
        ResultTabPanel scriptTab = findScriptTab();
        if (scriptTab == null) {
            scriptTab = new ResultTabPanel(ResultTabKind.SCRIPT);
            resultTabs.addTab(SCRIPT_TAB_TITLE, scriptTab);
        }
        scriptTab.setScriptText(script);
        resultTabs.setSelectedComponent(scriptTab);
        statusLabel.setText(scriptTab.statusText());
        onResultTabSelectionChanged();
    }

    private ResultTabPanel findScriptTab() {
        for (int i = FIRST_DYNAMIC_TAB_INDEX; i < resultTabs.getTabCount(); i++) {
            ResultTabPanel panel = getResultTabAt(i);
            if (panel != null && panel.kind() == ResultTabKind.SCRIPT) {
                return panel;
            }
        }
        return null;
    }

    private Path resolveFolderPath(String pathText) throws IOException {
        Path input = Paths.get(pathText.trim());
        if (input.isAbsolute()) {
            return PathUtil.resolveForAccess(input);
        }
        if (currentPath == null) {
            return PathUtil.resolveForAccess(input);
        }
        return PathUtil.resolveForAccess(currentPath.resolve(input).normalize());
    }

    private static boolean allFilesUnderBase(List<FileEntry> entries, Path hierarchyBase) {
        Path normalizedBase = hierarchyBase.toAbsolutePath().normalize();
        for (FileEntry entry : entries) {
            if (entry.directory()) {
                continue;
            }
            if (!entry.path().toAbsolutePath().normalize().startsWith(normalizedBase)) {
                return false;
            }
        }
        return true;
    }

    private List<FileEntry> pathsToFileEntries(Collection<Path> paths) {
        Map<Path, FileEntry> unique = new LinkedHashMap<>();
        boolean includeMetadata = fetchMetadata();
        for (Path path : paths) {
            FileEntry entry = fs.toFileEntry(path, includeMetadata);
            unique.putIfAbsent(entry.path().toAbsolutePath().normalize(), entry);
        }
        return new ArrayList<>(unique.values());
    }

    private void updateTargetTabCombo() {
        String previous = targetTabCombo.getSelectedItem() != null
                ? targetTabCombo.getSelectedItem().toString()
                : FILE_LIST_NEW_TAB;
        ResultTabPanel selected = getSelectedResultTab();
        String excludeTitle = null;
        if (selected != null && selected.kind() == ResultTabKind.FILE_LIST) {
            int selectedIndex = resultTabs.getSelectedIndex();
            if (selectedIndex >= 0) {
                excludeTitle = resultTabs.getTitleAt(selectedIndex);
            }
        }
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        model.addElement(FILE_LIST_NEW_TAB);
        for (int i = FIRST_DYNAMIC_TAB_INDEX; i < resultTabs.getTabCount(); i++) {
            ResultTabPanel panel = getResultTabAt(i);
            if (panel != null && panel.kind() == ResultTabKind.FILE_LIST) {
                String title = resultTabs.getTitleAt(i);
                if (!title.equals(excludeTitle)) {
                    model.addElement(title);
                }
            }
        }
        targetTabCombo.setModel(model);
        if (model.getIndexOf(previous) >= 0) {
            targetTabCombo.setSelectedItem(previous);
        } else {
            targetTabCombo.setSelectedItem(FILE_LIST_NEW_TAB);
        }
    }

    private void onResultTabSelectionChanged() {
        ResultTabPanel selected = getSelectedResultTab();
        boolean fileListTabSelected = selected != null && selected.kind() == ResultTabKind.FILE_LIST;
        if (fileListTabSelected) {
            addResultTabBtn.setText("マージ");
        } else {
            addResultTabBtn.setText("ファイルリスト追加");
        }
        updateTargetTabCombo();
        if (fileListTabSelected && currentPath != null) {
            String currentPathText = PathUtil.toDisplay(currentPath);
            if (copyBaseField.getText().isBlank()) {
                copyBaseField.setText(currentPathText);
            }
        }
        updateBusyControls();
        if (loading) {
            if (!taskStatusText.isBlank()) {
                statusLabel.setText(taskStatusText);
            }
            return;
        }
        if (selected != null) {
            statusLabel.setText(selected.statusText());
        } else if (resultTabs.getSelectedIndex() == SETTINGS_TAB_INDEX) {
            statusLabel.setText("設定");
        } else {
            updateStatusBar(null, "フォルダ一覧");
        }
    }

    private String allocateGrepResultTitle() {
        return GREP_TAB_PREFIX + (nextGrepResultNumber++);
    }

    private String allocateFileListTitle() {
        return FILE_LIST_TAB_PREFIX + (nextFileListNumber++);
    }

    private void installResultTabContextMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem renameItem = new JMenuItem("名前の変更");
        renameItem.addActionListener(e -> {
            int index = resultTabs.getSelectedIndex();
            if (!isFixedTabIndex(index)) {
                renameResultTabAt(index);
            }
        });
        JMenuItem closeItem = new JMenuItem("タブを閉じる");
        closeItem.addActionListener(e -> {
            int index = resultTabs.getSelectedIndex();
            if (!isFixedTabIndex(index)) {
                closeResultTabAt(index);
            }
        });
        popup.add(renameItem);
        popup.add(closeItem);

        resultTabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showPopup(e);
            }

            private void showPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int index = resultTabs.indexAtLocation(e.getX(), e.getY());
                if (isFixedTabIndex(index)) {
                    return;
                }
                resultTabs.setSelectedIndex(index);
                popup.show(resultTabs, e.getX(), e.getY());
            }
        });
    }

    private void renameResultTabAt(int index) {
        if (isFixedTabIndex(index) || index >= resultTabs.getTabCount()) {
            return;
        }
        String current = resultTabs.getTitleAt(index);
        String newTitle = JOptionPane.showInputDialog(this, "タブ名:", current);
        if (newTitle == null) {
            return;
        }
        newTitle = newTitle.trim();
        if (newTitle.isEmpty()) {
            statusLabel.setText("タブ名を入力してください");
            return;
        }
        if (isFixedTabTitle(newTitle)) {
            statusLabel.setText("このタブ名は使用できません");
            return;
        }
        for (int i = 0; i < resultTabs.getTabCount(); i++) {
            if (i != index && newTitle.equals(resultTabs.getTitleAt(i))) {
                statusLabel.setText("同じタブ名が既に存在します");
                return;
            }
        }
        resultTabs.setTitleAt(index, newTitle);
        updateTargetTabCombo();
    }

    private int findTabIndexByTitle(String title) {
        for (int i = FIRST_DYNAMIC_TAB_INDEX; i < resultTabs.getTabCount(); i++) {
            if (title.equals(resultTabs.getTitleAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private ResultTabPanel getResultTabAt(int index) {
        Component component = resultTabs.getComponentAt(index);
        return component instanceof ResultTabPanel panel ? panel : null;
    }

    private ResultTabPanel getSelectedGrepResultTab() {
        ResultTabPanel panel = getSelectedResultTab();
        return panel != null && panel.kind() == ResultTabKind.GREP_RESULT ? panel : null;
    }

    private ResultTabPanel getSelectedFileListTab() {
        ResultTabPanel panel = getSelectedResultTab();
        return panel != null && panel.kind() == ResultTabKind.FILE_LIST ? panel : null;
    }

    private List<Path> getActiveTabGrepPaths() {
        ResultTabPanel selected = getSelectedResultTab();
        if (selected == null) {
            return List.of();
        }
        return selected.grepTargetPaths();
    }

    private static List<Path> uniquePathsFromGrepMatches(List<GrepMatch> matches) {
        Map<Path, Path> unique = new LinkedHashMap<>();
        for (GrepMatch match : matches) {
            if (!match.matched()) {
                continue;
            }
            Path normalized = match.path().toAbsolutePath().normalize();
            unique.putIfAbsent(normalized, normalized);
        }
        return List.copyOf(unique.values());
    }

    private static List<FileEntry> mergeFileEntries(List<FileEntry> existing, List<FileEntry> incoming) {
        Map<Path, FileEntry> merged = new LinkedHashMap<>();
        for (FileEntry entry : existing) {
            merged.put(entry.path().toAbsolutePath().normalize(), entry);
        }
        for (FileEntry entry : incoming) {
            Path key = entry.path().toAbsolutePath().normalize();
            FileEntry previous = merged.get(key);
            merged.put(key, previous != null ? mergeFileEntryMetadata(previous, entry) : entry);
        }
        return new ArrayList<>(merged.values());
    }

    private static FileEntry mergeFileEntryMetadata(FileEntry existing, FileEntry incoming) {
        return new FileEntry(
                incoming.name(),
                incoming.path(),
                incoming.directory(),
                incoming.size() != null ? incoming.size() : existing.size(),
                incoming.modified() != null ? incoming.modified() : existing.modified()
        );
    }

    private static List<GrepMatch> mergeGrepMatches(List<GrepMatch> existing, List<GrepMatch> incoming) {
        Map<String, GrepMatch> merged = new LinkedHashMap<>();
        for (GrepMatch match : existing) {
            merged.put(grepMatchKey(match), match);
        }
        for (GrepMatch match : incoming) {
            merged.merge(grepMatchKey(match), match, (previous, newer) ->
                    newer.matched() ? newer : previous);
        }
        return new ArrayList<>(merged.values());
    }

    private static String grepMatchKey(GrepMatch match) {
        return match.path().toAbsolutePath().normalize() + ":" + match.lineNumber();
    }

    private void closeResultTabAt(int index) {
        if (isFixedTabIndex(index) || index >= resultTabs.getTabCount()) {
            return;
        }
        resultTabs.removeTabAt(index);
        updateTargetTabCombo();
        onResultTabSelectionChanged();
    }

    private ResultTabPanel getSelectedResultTab() {
        Component selected = resultTabs.getSelectedComponent();
        return selected instanceof ResultTabPanel panel ? panel : null;
    }

    private void runGrep() {
        if (currentPath == null || loading) {
            return;
        }
        ResultTabPanel activeTab = getSelectedResultTab();
        if (activeTab != null && activeTab.kind() == ResultTabKind.GREP_RESULT) {
            return;
        }
        String pattern = grepField.getText().trim();

        cancelActiveWorker();
        searchCancel.set(false);

        List<Path> explicitPaths = getActiveTabGrepPaths();
        if (activeTab != null && explicitPaths.isEmpty()) {
            statusLabel.setText("Grep 対象のファイルがありません");
            return;
        }

        commitGrepFieldHistory();

        final GrepOptions options;
        try {
            options = buildGrepOptions();
        } catch (IOException ex) {
            showError(formatError(ex));
            return;
        }

        String prepareLabel = pattern.isEmpty() ? "ファイル一覧 準備" : "Grep 準備";
        beginTask(ActiveTask.GREP, TaskProgress.of(prepareLabel, 0, -1, System.currentTimeMillis()).formatStatus());

        boolean regex = grepRegexCheck.isSelected();
        boolean recursive = grepRecursiveCheck.isSelected();
        int contextLines = ((Number) grepContextSpinner.getValue()).intValue();

        final long progressStart = taskStartedAtMs;
        SwingWorker<GrepResult, Void> worker = new SwingWorker<>() {
            @Override
            protected GrepResult doInBackground() throws Exception {
                Consumer<TaskProgress> onProgress = progress -> SwingUtilities.invokeLater(() -> updateTaskProgress(progress));
                if (!explicitPaths.isEmpty()) {
                    return fs.grepPaths(
                            explicitPaths, options, pattern, regex, false, recursive, contextLines,
                            searchCancel, progressStart, onProgress);
                }
                return fs.grepText(
                        options, pattern, regex, false, recursive, contextLines, searchCancel, progressStart, onProgress);
            }

            @Override
            protected void done() {
                finishActiveWorker(this, result -> applyGrepResult(result), cause -> {
                    if (cause instanceof java.util.regex.PatternSyntaxException) {
                        showError("正規表現が無効です: " + cause.getMessage());
                    } else {
                        showError(formatError(cause));
                    }
                });
            }
        };
        activeWorker = worker;
        worker.execute();
    }

    private void applyGrepResult(GrepResult result) {
        ResultTabPanel panel = new ResultTabPanel(ResultTabKind.GREP_RESULT);
        panel.setGrepMatches(result.matches(), result.root(), false);
        resultTabs.addTab(allocateGrepResultTitle(), panel);
        resultTabs.setSelectedComponent(panel);
        String mode = formatGrepResultMode(result);
        statusLabel.setText(panel.statusText() + "  |  " + result.elapsedMs() + " ms  |  " + mode);
        updateTargetTabCombo();
        onResultTabSelectionChanged();
    }

    private static String formatGrepResultMode(GrepResult result) {
        long hitCount = result.matches().stream().filter(GrepMatch::matched).count();
        int rowCount = result.matches().size();
        boolean fileListOnly = rowCount > 0 && result.matches().stream()
                .allMatch(match -> match.matched() && match.lineNumber() == 0);
        if (fileListOnly) {
            return "ファイル一覧: " + hitCount + " ファイル";
        }
        String countText = hitCount == rowCount
                ? hitCount + " 件"
                : hitCount + " 件 (" + rowCount + " 行)";
        return "Grep: " + countText + " / " + result.filesScanned() + " ファイル";
    }

    private GrepOptions buildGrepOptions() throws IOException {
        List<Path> fileListPaths = getActiveTabGrepPaths();
        Path searchRoot;
        List<String> pathPatterns;
        if (!fileListPaths.isEmpty()) {
            // ファイルリスト Grep: パス欄は開始パスではなくフィルタ（部分一致 / glob）
            searchRoot = currentPath != null
                    ? currentPath
                    : fileListPaths.stream().map(Path::getParent).filter(p -> p != null).findFirst().orElse(null);
            pathPatterns = parseCommaList(grepPathField.getText());
        } else {
            searchRoot = resolveGrepSearchRoot();
            pathPatterns = List.of();
        }
        if (searchRoot == null) {
            searchRoot = Paths.get(System.getProperty("user.home"));
        }
        String fileNamePattern = grepFileNameField.getText().trim();
        List<String> extensions = parseExtensionList(grepExtensionField.getText());
        return new GrepOptions(searchRoot, pathPatterns, fileNamePattern, extensions);
    }

    private Path resolveGrepSearchRoot() throws IOException {
        String pathText = grepPathField.getText().trim();
        if (pathText.isEmpty()) {
            if (currentPath == null) {
                throw new IOException("現在のフォルダが未設定です");
            }
            return currentPath;
        }
        Path input = Paths.get(pathText);
        if (input.isAbsolute()) {
            return PathUtil.resolveForAccess(input);
        }
        if (currentPath == null) {
            return PathUtil.resolveForAccess(input);
        }
        return PathUtil.resolveForAccess(currentPath.resolve(input).normalize());
    }

    private static List<String> parseExtensionList(String text) {
        if (text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("[,;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void openGrepMatch(GrepMatch match) {
        String editorCommand = grepEditorField.getText().trim();
        if (editorCommand.isEmpty()) {
            showError("設定タブでエディタコマンドを指定してください（例: code -g {file}:{line}）");
            return;
        }
        try {
            Path path = resolvePathForEditorOpen(match.path());
            int line = Math.max(1, match.lineNumber());
            ExternalEditorOpener.openAtLine(editorCommand, path, line);
            grepEditorField.commitHistory();
            statusLabel.setText("エディタで開きました: " + PathUtil.toDisplay(path) + ":" + line);
        } catch (IOException ex) {
            showError(formatError(ex));
        }
    }

    private Path resolvePathForEditorOpen(Path path) throws IOException {
        if (PathUtil.isUnc(path)) {
            statusLabel.setText("ローカルにコピー中… " + path.getFileName());
            return LocalFileOpener.copyToLocalTemp(path);
        }
        return PathUtil.resolveForAccess(path);
    }

    private void openFile(Path path, boolean useLocalCopyForUnc) {
        try {
            if (useLocalCopyForUnc && PathUtil.isUnc(path)) {
                statusLabel.setText("ローカルにコピー中… " + path.getFileName());
                Path localCopy = LocalFileOpener.copyToLocalTemp(path);
                Desktop.getDesktop().open(localCopy.toFile());
                statusLabel.setText("開きました: " + PathUtil.toDisplay(localCopy));
                return;
            }
            Desktop.getDesktop().open(PathUtil.resolveForAccess(path).toFile());
        } catch (IOException ex) {
            showError(formatError(ex));
        }
    }

    private void openFileOnServer(Path path) {
        openFile(path, false);
    }

    private void updateStatusBar(Long elapsedMs, String mode) {
        int visible = table.getRowCount();
        String countText = visible == totalEntryCount
                ? totalEntryCount + " 項目"
                : visible + " / " + totalEntryCount + " 項目";
        String timeText = elapsedMs != null ? "  |  " + elapsedMs + " ms" : "";
        statusLabel.setText(countText + timeText + "  |  " + mode);
    }

    private void cancelActiveWorker() {
        searchCancel.set(true);
        if (activeWorker != null && !activeWorker.isDone()) {
            activeWorker.cancel(true);
        }
    }

    private void requestCancelActiveTask(String taskName) {
        if (activeTask != ActiveTask.SEARCH
                && activeTask != ActiveTask.INDEX
                && activeTask != ActiveTask.GREP) {
            return;
        }
        cancelActiveWorker();
        statusLabel.setText(taskName + " をキャンセルしました");
    }

    private <T> void finishActiveWorker(SwingWorker<T, Void> worker, Consumer<T> onSuccess) {
        finishActiveWorker(worker, onSuccess, cause -> showError(formatError(cause)));
    }

    private <T> void finishActiveWorker(
            SwingWorker<T, Void> worker,
            Consumer<T> onSuccess,
            Consumer<Throwable> onError
    ) {
        if (activeWorker != worker) {
            return;
        }
        activeWorker = null;
        ActiveTask finishedTask = activeTask;
        endTask();
        try {
            if (worker.isCancelled()) {
                if (finishedTask == ActiveTask.SEARCH) {
                    statusLabel.setText("検索をキャンセルしました");
                } else if (finishedTask == ActiveTask.INDEX) {
                    statusLabel.setText("インデックスをキャンセルしました");
                } else if (finishedTask == ActiveTask.GREP) {
                    statusLabel.setText("Grep をキャンセルしました");
                }
                return;
            }
            onSuccess.accept(worker.get());
        } catch (CancellationException ignored) {
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            if (searchCancel.get()) {
                return;
            }
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof CancellationException) {
                return;
            }
            onError.accept(cause);
        } catch (Exception ex) {
            if (searchCancel.get()) {
                return;
            }
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof CancellationException) {
                return;
            }
            onError.accept(cause);
        }
    }

    private void openEntry(FileEntry entry) {
        try {
            Path target = PathUtil.resolveForAccess(entry.path());
            if (entry.directory()) {
                clearNavigationState();
                loadDirectory(target, true, false);
                return;
            }
            openFile(entry.path(), true);
        } catch (IOException ex) {
            showError(formatError(ex));
        }
    }

    private static String formatError(Throwable cause) {
        if (cause instanceof java.nio.file.AccessDeniedException) {
            return "アクセスが拒否されました:\n" + cause.getMessage();
        }
        if (cause instanceof java.nio.file.NoSuchFileException) {
            return "パスが見つかりません:\n" + cause.getMessage();
        }
        if (cause instanceof java.nio.file.NotDirectoryException) {
            return "フォルダではありません:\n" + cause.getMessage();
        }
        String message = cause.getMessage();
        return message != null ? message : cause.toString();
    }

    private void goUp() {
        if (currentPath == null) {
            return;
        }
        Path parent = fs.getParent(currentPath);
        if (!parent.equals(currentPath)) {
            clearNavigationState();
            loadDirectory(parent, true, false);
        }
    }

    private void goBack() {
        if (historyIndex > 0) {
            historyIndex--;
            clearNavigationState();
            loadDirectory(history.get(historyIndex), false, false);
        }
    }

    private void goForward() {
        if (historyIndex < history.size() - 1) {
            historyIndex++;
            clearNavigationState();
            loadDirectory(history.get(historyIndex), false, false);
        }
    }

    private void updateNavButtons() {
        updateBusyControls();
    }

    private void showError(String message) {
        statusLabel.setText(message);
        JOptionPane.showMessageDialog(this, message, "エラー", JOptionPane.ERROR_MESSAGE);
    }

    private enum ResultTabKind {
        GREP_RESULT,
        FILE_LIST,
        SCRIPT
    }

    private final class ResultTabPanel extends JPanel {

        private final ResultTabKind kind;
        private final FileTableModel fileModel = new FileTableModel();
        private final GrepResultTableModel grepModel = new GrepResultTableModel();
        private final JTable resultTable;
        private final JTextArea scriptArea;
        private Path displayBase;
        private List<Path> scopePaths = List.of();

        ResultTabPanel(ResultTabKind kind) {
            super(new BorderLayout());
            this.kind = kind;
            if (kind == ResultTabKind.SCRIPT) {
                resultTable = null;
                scriptArea = new JTextArea();
                scriptArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                scriptArea.setEditable(true);
                scriptArea.setLineWrap(false);
                scriptArea.setTabSize(4);
                installScriptActions();
                add(new JScrollPane(scriptArea), BorderLayout.CENTER);
            } else {
                scriptArea = null;
                if (kind == ResultTabKind.GREP_RESULT) {
                    resultTable = new JTable(grepModel);
                    configureResultGrepTable(resultTable);
                } else {
                    fileModel.setShowFullPath(true);
                    resultTable = new JTable(fileModel);
                    configureResultFileTable(resultTable, fileModel);
                }
                resultTable.setRowHeight(28);
                resultTable.setShowGrid(false);
                resultTable.setIntercellSpacing(new Dimension(0, 0));
                resultTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
                resultTable.getTableHeader().setReorderingAllowed(false);
                installResultTableActions();
                installResultTableKeyBindings();
                add(new JScrollPane(resultTable), BorderLayout.CENTER);
            }
        }

        ResultTabKind kind() {
            return kind;
        }

        void setScriptText(String text) {
            if (kind != ResultTabKind.SCRIPT || scriptArea == null) {
                return;
            }
            scriptArea.setText(text);
            scriptArea.setCaretPosition(0);
        }

        String scriptText() {
            return scriptArea != null ? scriptArea.getText() : "";
        }

        private void installScriptActions() {
            JPopupMenu popup = new JPopupMenu();
            JMenuItem copyAllItem = new JMenuItem("スクリプトをコピー");
            copyAllItem.addActionListener(e -> copyToClipboard(scriptText()));
            popup.add(copyAllItem);

            scriptArea.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    showPopup(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    showPopup(e);
                }

                private void showPopup(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        popup.show(scriptArea, e.getX(), e.getY());
                    }
                }
            });
        }

        List<FileEntry> fileEntries() {
            return kind == ResultTabKind.FILE_LIST ? List.copyOf(fileModel.getEntries()) : List.of();
        }

        List<FileEntry> selectedFileEntriesForCopy() {
            if (kind != ResultTabKind.FILE_LIST) {
                return List.of();
            }
            int[] viewRows = resultTable.getSelectedRows();
            if (viewRows.length == 0) {
                return fileEntries().stream()
                        .filter(entry -> !entry.directory())
                        .toList();
            }
            List<FileEntry> selected = new ArrayList<>();
            for (int viewRow : viewRows) {
                FileEntry entry = fileModel.getEntry(resultTable.convertRowIndexToModel(viewRow));
                if (entry != null && !entry.directory()) {
                    selected.add(entry);
                }
            }
            return selected;
        }

        void setFileEntries(List<FileEntry> entries, Path displayBase, boolean merge) {
            if (kind != ResultTabKind.FILE_LIST) {
                return;
            }
            this.displayBase = displayBase;
            List<FileEntry> combined = merge
                    ? mergeFileEntries(fileModel.getEntries(), entries)
                    : List.copyOf(entries);
            fileModel.setContent(combined, null);
            showFileTable();
            refreshScopePaths();
        }

        void setGrepMatches(List<GrepMatch> matches, Path displayBase, boolean merge) {
            if (kind != ResultTabKind.GREP_RESULT) {
                return;
            }
            this.displayBase = displayBase;
            List<GrepMatch> combined = merge
                    ? mergeGrepMatches(grepModel.getMatches(), matches)
                    : List.copyOf(matches);
            grepModel.setMatches(combined, displayBase);
            showGrepTable();
            refreshScopePaths();
        }

        List<Path> scopePaths() {
            return scopePaths;
        }

        /**
         * Grep 対象パス。ファイルリストでは表示内容から都度計算し、選択行があればそれを優先する。
         * フォルダも含める（「サブフォルダ」ON なら配下を再帰検索）。
         */
        List<Path> grepTargetPaths() {
            if (kind == ResultTabKind.GREP_RESULT) {
                return uniquePathsFromGrepMatches(grepModel.getMatches());
            }
            if (kind != ResultTabKind.FILE_LIST) {
                return List.of();
            }
            List<FileEntry> sources = selectedFileEntriesForGrep();
            if (sources.isEmpty()) {
                sources = List.copyOf(fileModel.getEntries());
            }
            Map<Path, Path> unique = new LinkedHashMap<>();
            for (FileEntry entry : sources) {
                Path normalized = normalizePathSafe(entry.path());
                unique.putIfAbsent(normalized, normalized);
            }
            return List.copyOf(unique.values());
        }

        /** Grep 用: ファイルとフォルダの両方。選択なしなら空（呼び出し側で全件）。 */
        List<FileEntry> selectedFileEntriesForGrep() {
            if (kind != ResultTabKind.FILE_LIST) {
                return List.of();
            }
            int[] viewRows = resultTable.getSelectedRows();
            if (viewRows.length == 0) {
                return List.of();
            }
            List<FileEntry> selected = new ArrayList<>();
            for (int viewRow : viewRows) {
                FileEntry entry = fileModel.getEntry(resultTable.convertRowIndexToModel(viewRow));
                if (entry != null) {
                    selected.add(entry);
                }
            }
            return selected;
        }

        private static Path normalizePathSafe(Path path) {
            try {
                return path.toAbsolutePath().normalize();
            } catch (Exception ex) {
                return path.normalize();
            }
        }

        String statusText() {
            if (kind == ResultTabKind.SCRIPT) {
                int lines = scriptArea.getLineCount();
                return lines + " 行  |  スクリプト";
            }
            String kindLabel = kind == ResultTabKind.GREP_RESULT ? "Grep 結果" : "ファイルリスト";
            if (kind == ResultTabKind.GREP_RESULT) {
                int matchCount = grepModel.matchCount();
                int rowCount = grepModel.getRowCount();
                String countLabel = matchCount == rowCount
                        ? matchCount + " 件"
                        : matchCount + " 件 (" + rowCount + " 行)";
                return countLabel + "  |  " + grepTargetPaths().size() + " ファイル  |  " + kindLabel;
            }
            long fileCount = fileModel.getEntries().stream().filter(entry -> !entry.directory()).count();
            return fileModel.getRowCount() + " 件  |  " + fileCount + " ファイル  |  " + kindLabel;
        }

        private void showFileTable() {
            resultTable.setModel(fileModel);
            resultTable.setRowSorter(new TableRowSorter<>(fileModel));
            configureResultFileTable(resultTable, fileModel);
            resultTable.revalidate();
            resultTable.repaint();
        }

        private void showGrepTable() {
            resultTable.setModel(grepModel);
            resultTable.setRowSorter(new TableRowSorter<>(grepModel));
            configureResultGrepTable(resultTable);
            resultTable.revalidate();
            resultTable.repaint();
        }

        private void refreshScopePaths() {
            if (kind == ResultTabKind.GREP_RESULT) {
                scopePaths = uniquePathsFromGrepMatches(grepModel.getMatches());
            } else if (kind == ResultTabKind.FILE_LIST) {
                Map<Path, Path> unique = new LinkedHashMap<>();
                for (FileEntry entry : fileModel.getEntries()) {
                    if (entry.directory()) {
                        continue;
                    }
                    Path normalized = normalizePathSafe(entry.path());
                    unique.putIfAbsent(normalized, normalized);
                }
                scopePaths = List.copyOf(unique.values());
            } else {
                scopePaths = List.of();
            }
        }

        private void configureResultFileTable(JTable target, FileTableModel model) {
            target.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(
                        JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                    Component component = super.getTableCellRendererComponent(
                            t, value, selected, focus, row, column);
                    int modelRow = t.convertRowIndexToModel(row);
                    FileEntry entry = model.getEntry(modelRow);
                    if (entry == null) {
                        return component;
                    }
                    if (!selected) {
                        component.setBackground(t.getBackground());
                        component.setForeground(FileTypeUtil.isTextFile(entry.name())
                                ? TEXT_FILE_COLOR : BINARY_FILE_COLOR);
                    }
                    return component;
                }
            });
            if (target.getColumnCount() < 5) {
                return;
            }
            target.getColumnModel().getColumn(0).setPreferredWidth(480);
            target.getColumnModel().getColumn(1).setPreferredWidth(160);
            target.getColumnModel().getColumn(2).setPreferredWidth(80);
            target.getColumnModel().getColumn(3).setPreferredWidth(100);
            target.getColumnModel().getColumn(4).setPreferredWidth(160);
        }

        private void configureResultGrepTable(JTable target) {
            if (target.getColumnCount() < 4) {
                return;
            }
            target.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(
                        JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                    Component component = super.getTableCellRendererComponent(
                            t, value, selected, focus, row, column);
                    if (!(t.getModel() instanceof GrepResultTableModel model)) {
                        return component;
                    }
                    GrepMatch match = model.getMatch(t.convertRowIndexToModel(row));
                    if (selected) {
                        component.setForeground(t.getSelectionForeground());
                        component.setBackground(t.getSelectionBackground());
                        return component;
                    }
                    component.setBackground(t.getBackground());
                    if (match != null && match.matched()) {
                        component.setForeground(GREP_HIT_COLOR);
                    } else if (match != null) {
                        component.setForeground(UIManager.getColor("Label.disabledForeground"));
                    } else {
                        component.setForeground(t.getForeground());
                    }
                    return component;
                }
            });
            target.getColumnModel().getColumn(0).setPreferredWidth(420);
            target.getColumnModel().getColumn(1).setPreferredWidth(140);
            target.getColumnModel().getColumn(2).setPreferredWidth(50);
            target.getColumnModel().getColumn(3).setPreferredWidth(500);
        }

        private void installResultTableActions() {
            JPopupMenu popup = new JPopupMenu();
            JMenuItem openServerFileItem = new JMenuItem("サーバのファイルを開く");
            openServerFileItem.addActionListener(e -> {
                Path path = selectedPath();
                if (path != null) {
                    openFileOnServer(path);
                }
            });
            JMenuItem copyPath = new JMenuItem("パスのコピー");
            copyPath.addActionListener(e -> {
                Path path = selectedPath();
                if (path != null) {
                    copyToClipboard(path.toString());
                }
            });
            JMenuItem copyLine = new JMenuItem("行のコピー");
            copyLine.addActionListener(e -> {
                GrepMatch match = selectedGrepMatch();
                if (match != null) {
                    copyToClipboard(match.line());
                }
            });
            popup.add(openServerFileItem);
            popup.add(copyPath);
            popup.add(copyLine);

            resultTable.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        openSelectedResult();
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    showPopup(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    showPopup(e);
                }

                private void showPopup(MouseEvent e) {
                    if (!e.isPopupTrigger()) {
                        return;
                    }
                    int row = resultTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        resultTable.setRowSelectionInterval(row, row);
                        copyLine.setVisible(kind == ResultTabKind.GREP_RESULT);
                        Path path = selectedPath();
                        boolean uncFile = path != null && PathUtil.isUnc(path);
                        if (kind == ResultTabKind.FILE_LIST) {
                            int modelRow = resultTable.convertRowIndexToModel(row);
                            FileEntry entry = fileModel.getEntry(modelRow);
                            uncFile = entry != null && !entry.directory() && PathUtil.isUnc(entry.path());
                        }
                        openServerFileItem.setVisible(uncFile);
                        popup.show(resultTable, e.getX(), e.getY());
                    }
                }
            });
        }

        private void installResultTableKeyBindings() {
            int menuShortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
            InputMap inputMap = resultTable.getInputMap(JComponent.WHEN_FOCUSED);
            ActionMap actionMap = resultTable.getActionMap();

            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, menuShortcut), "selectAllRows");
            actionMap.put("selectAllRows", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    selectAllResultRows();
                }
            });

            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menuShortcut), "copyResultRows");
            actionMap.put("copyResultRows", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    copySelectedResultRows();
                }
            });
        }

        private void selectAllResultRows() {
            int rowCount = resultTable.getRowCount();
            if (rowCount > 0) {
                resultTable.setRowSelectionInterval(0, rowCount - 1);
            }
        }

        private void copySelectedResultRows() {
            int[] viewRows = resultTable.getSelectedRows();
            if (viewRows.length == 0) {
                return;
            }
            if (kind == ResultTabKind.FILE_LIST) {
                copyFileListPaths(viewRows);
            } else {
                copyGrepResultsTsv(viewRows);
            }
        }

        private void copyFileListPaths(int[] viewRows) {
            StringBuilder text = new StringBuilder();
            for (int viewRow : viewRows) {
                FileEntry entry = fileModel.getEntry(resultTable.convertRowIndexToModel(viewRow));
                if (entry == null) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(PathUtil.toDisplay(entry.path()));
            }
            if (text.isEmpty()) {
                return;
            }
            copyBulkToClipboard(text.toString(), viewRows.length + " 件のパス");
        }

        private void copyGrepResultsTsv(int[] viewRows) {
            StringBuilder text = new StringBuilder();
            for (int col = 0; col < grepModel.getColumnCount(); col++) {
                if (col > 0) {
                    text.append('\t');
                }
                text.append(tsvCell(grepModel.getColumnName(col)));
            }
            for (int viewRow : viewRows) {
                text.append('\n');
                int modelRow = resultTable.convertRowIndexToModel(viewRow);
                for (int col = 0; col < grepModel.getColumnCount(); col++) {
                    if (col > 0) {
                        text.append('\t');
                    }
                    Object value = grepModel.getValueAt(modelRow, col);
                    text.append(tsvCell(value != null ? value.toString() : ""));
                }
            }
            copyBulkToClipboard(text.toString(), viewRows.length + " 行 (TSV)");
        }

        private static String tsvCell(String value) {
            if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                return "\"" + value.replace("\"", "\"\"") + "\"";
            }
            return value;
        }

        private void copyBulkToClipboard(String text, String summary) {
            StringSelection selection = new StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
            statusLabel.setText("コピーしました: " + summary);
        }

        private GrepMatch selectedGrepMatch() {
            if (kind != ResultTabKind.GREP_RESULT) {
                return null;
            }
            int viewRow = resultTable.getSelectedRow();
            if (viewRow < 0) {
                return null;
            }
            return grepModel.getMatch(resultTable.convertRowIndexToModel(viewRow));
        }

        private Path selectedPath() {
            int viewRow = resultTable.getSelectedRow();
            if (viewRow < 0) {
                return null;
            }
            int modelRow = resultTable.convertRowIndexToModel(viewRow);
            if (kind == ResultTabKind.GREP_RESULT) {
                GrepMatch match = grepModel.getMatch(modelRow);
                return match != null ? match.path() : null;
            }
            FileEntry entry = fileModel.getEntry(modelRow);
            return entry != null ? entry.path() : null;
        }

        private void openSelectedResult() {
            int viewRow = resultTable.getSelectedRow();
            if (viewRow < 0) {
                return;
            }
            int modelRow = resultTable.convertRowIndexToModel(viewRow);
            if (kind == ResultTabKind.GREP_RESULT) {
                GrepMatch match = grepModel.getMatch(modelRow);
                if (match != null) {
                    openGrepMatch(match);
                }
            } else {
                FileEntry entry = fileModel.getEntry(modelRow);
                if (entry != null) {
                    openEntry(entry);
                }
            }
        }
    }

    private static final class FileTableModel extends AbstractTableModel {

        private static final String[] COLS = {"名前", "種類", "サイズ", "更新日時"};
        private static final String[] FULL_PATH_COLS = {"パス", "ファイル名", "種類", "サイズ", "更新日時"};
        private List<FileEntry> entries = List.of();
        private Path displayBase;
        private boolean showFullPath;

        void setShowFullPath(boolean showFullPath) {
            this.showFullPath = showFullPath;
            fireTableStructureChanged();
        }

        void setEntries(List<FileEntry> entries) {
            this.entries = entries;
            fireTableDataChanged();
        }

        void setContent(List<FileEntry> entries, Path displayBase) {
            this.entries = entries;
            this.displayBase = displayBase;
            fireTableDataChanged();
        }

        void setDisplayBase(Path displayBase) {
            this.displayBase = displayBase;
            fireTableDataChanged();
        }

        FileEntry getEntry(int row) {
            if (row < 0 || row >= entries.size()) {
                return null;
            }
            return entries.get(row);
        }

        List<FileEntry> getEntries() {
            return entries;
        }

        Path getDisplayBase() {
            return displayBase;
        }

        @Override
        public int getColumnCount() {
            return showFullPath ? FULL_PATH_COLS.length : COLS.length;
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public String getColumnName(int column) {
            return (showFullPath ? FULL_PATH_COLS : COLS)[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            if (row < 0 || row >= entries.size()) {
                return "";
            }
            FileEntry e = entries.get(row);
            if (showFullPath) {
                return switch (column) {
                    case 0 -> formatPath(e);
                    case 1 -> e.name();
                    case 2 -> FileTypeUtil.typeDisplay(e);
                    case 3 -> e.directory() ? "—" : formatSize(e.size());
                    case 4 -> e.modified() != null ? DATE_FMT.format(e.modified()) : "—";
                    default -> "";
                };
            }
            return switch (column) {
                case 0 -> formatName(e);
                case 1 -> FileTypeUtil.typeDisplay(e);
                case 2 -> e.directory() ? "—" : formatSize(e.size());
                case 3 -> e.modified() != null ? DATE_FMT.format(e.modified()) : "—";
                default -> "";
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return String.class;
        }

        private String formatPath(FileEntry e) {
            return (e.directory() ? "📁 " : "📄 ") + PathUtil.toDisplay(e.path());
        }

        private String formatName(FileEntry e) {
            String label = displayBase != null
                    ? displayBase.relativize(e.path()).toString()
                    : e.name();
            return (e.directory() ? "📁 " : "📄 ") + label;
        }

        private static String formatSize(Long bytes) {
            if (bytes == null) {
                return "—";
            }
            if (bytes < 1024) {
                return bytes + " B";
            }
            if (bytes < 1024 * 1024) {
                return String.format("%.1f KB", bytes / 1024.0);
            }
            if (bytes < 1024L * 1024 * 1024) {
                return String.format("%.1f MB", bytes / (1024.0 * 1024));
            }
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
