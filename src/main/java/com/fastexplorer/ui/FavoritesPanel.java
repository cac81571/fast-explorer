package com.fastexplorer.ui;

import com.fastexplorer.config.BookmarkStore;
import com.fastexplorer.model.BookmarkNode;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class FavoritesPanel extends JPanel {

    private static final Color FOLDER_COLOR = new Color(28, 100, 171);
    private static final Color SEARCH_COLOR = new Color(0, 120, 70);
    private static final Color GREP_COLOR = new Color(140, 75, 30);

    private final Consumer<String> onNavigate;
    private final Runnable onChanged;
    private final Supplier<String> currentPathSupplier;
    private final Supplier<BookmarkSearchPreset> currentSearchSupplier;
    private final Consumer<BookmarkSearchPreset> applySearch;
    private final Runnable runSearch;
    private final Supplier<BookmarkGrepPreset> currentGrepSupplier;
    private final Consumer<BookmarkGrepPreset> applyGrep;
    private final Runnable runGrep;

    private final BookmarkNode rootFolder = BookmarkNode.folder("お気に入り");
    private final DefaultMutableTreeNode rootTreeNode = new DefaultMutableTreeNode(rootFolder);
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootTreeNode);
    private final JTree tree = new JTree(treeModel);
    private final JPopupMenu popup = new JPopupMenu();

    FavoritesPanel(
            Consumer<String> onNavigate,
            Runnable onChanged,
            Supplier<String> currentPathSupplier,
            Supplier<BookmarkSearchPreset> currentSearchSupplier,
            Consumer<BookmarkSearchPreset> applySearch,
            Runnable runSearch,
            Supplier<BookmarkGrepPreset> currentGrepSupplier,
            Consumer<BookmarkGrepPreset> applyGrep,
            Runnable runGrep
    ) {
        this.onNavigate = onNavigate;
        this.onChanged = onChanged;
        this.currentPathSupplier = currentPathSupplier;
        this.currentSearchSupplier = currentSearchSupplier;
        this.applySearch = applySearch;
        this.runSearch = runSearch;
        this.currentGrepSupplier = currentGrepSupplier;
        this.applyGrep = applyGrep;
        this.runGrep = runGrep;

        setLayout(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 0));
        setPreferredSize(new Dimension(240, 0));
        setMinimumSize(new Dimension(140, 0));

        JLabel title = new JLabel("お気に入り");
        title.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        JButton addFolderBtn = new JButton("＋");
        addFolderBtn.setMargin(new Insets(2, 4, 2, 4));
        addFolderBtn.setToolTipText("フォルダを追加");
        addFolderBtn.addActionListener(e -> addFolder());

        JButton addPathBtn = new JButton("★");
        addPathBtn.setMargin(new Insets(2, 4, 2, 4));
        addPathBtn.setToolTipText("現在のパスを追加");
        addPathBtn.addActionListener(e -> addCurrentPathFromToolbar());

        actions.add(addFolderBtn);
        actions.add(addPathBtn);

        JPanel header = new JPanel(new BorderLayout());
        header.add(title, BorderLayout.WEST);
        header.add(actions, BorderLayout.EAST);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new BookmarkTreeCellRenderer());
        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON_OR_INSERT);
        tree.setTransferHandler(new BookmarkTransferHandler());
        installTreeActions();

        add(header, BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    void loadBookmarks(List<BookmarkNode> bookmarks) {
        rootFolder.children().clear();
        rootFolder.children().addAll(bookmarks != null ? bookmarks : BookmarkStore.load());
        rebuildTree();
    }

    List<BookmarkNode> getRootChildren() {
        return List.copyOf(rootFolder.children());
    }

    void addCurrentPath(String pathText) {
        if (pathText == null || pathText.isBlank()) {
            return;
        }
        String name = defaultPathName(pathText);
        String label = JOptionPane.showInputDialog(this, "表示名:", name);
        if (label == null) {
            return;
        }
        label = label.trim();
        if (label.isEmpty()) {
            label = name;
        }
        addPath(pathText, label);
    }

    void addCurrentSearch() {
        BookmarkSearchPreset preset = currentSearchSupplier.get();
        if (preset == null || preset.isEmpty()) {
            JOptionPane.showMessageDialog(this, "検索条件が空です", "お気に入り", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String label = JOptionPane.showInputDialog(this, "表示名:", preset.defaultName());
        if (label == null) {
            return;
        }
        label = label.trim();
        if (label.isEmpty()) {
            label = preset.defaultName();
        }
        selectedFolderFor(BookmarkNode.Kind.SEARCH).children().add(BookmarkNode.search(
                label,
                preset.pathPatterns(),
                preset.filePatterns(),
                preset.extensions()
        ));
        rebuildTree();
        onChanged.run();
    }

    void addCurrentGrep() {
        BookmarkGrepPreset preset = currentGrepSupplier.get();
        if (preset == null || preset.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Grep 条件が空です", "お気に入り", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String label = JOptionPane.showInputDialog(this, "表示名:", preset.defaultName());
        if (label == null) {
            return;
        }
        label = label.trim();
        if (label.isEmpty()) {
            label = preset.defaultName();
        }
        selectedFolderFor(BookmarkNode.Kind.GREP).children().add(BookmarkNode.grep(
                label,
                preset.pattern(),
                preset.path(),
                preset.file(),
                preset.extension(),
                preset.regex(),
                preset.recursive(),
                preset.context()
        ));
        rebuildTree();
        onChanged.run();
    }

    private void addCurrentPathFromToolbar() {
        String path = currentPathSupplier.get();
        if (path == null || path.isBlank()) {
            promptAddPath(null);
        } else {
            addCurrentPath(path);
        }
    }

    private void promptAddPath(String pathText) {
        String initialPath = pathText != null ? pathText : "";
        String path = JOptionPane.showInputDialog(this, "パス:", initialPath);
        if (path == null) {
            return;
        }
        path = path.trim();
        if (path.isEmpty()) {
            return;
        }
        String name = JOptionPane.showInputDialog(this, "表示名:", defaultPathName(path));
        if (name == null) {
            return;
        }
        name = name.trim();
        if (name.isEmpty()) {
            name = defaultPathName(path);
        }
        addPath(path, name);
    }

    private void addPath(String path, String name) {
        BookmarkNode parent = selectedFolderFor(BookmarkNode.Kind.PATH);
        if (containsPath(categoryOf(BookmarkNode.Kind.PATH), path)) {
            JOptionPane.showMessageDialog(this, "同じパスが既に存在します", "お気に入り", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        parent.children().add(BookmarkNode.path(path, name));
        rebuildTree();
        onChanged.run();
    }

    private void addFolder() {
        BookmarkNode context = selectedFolder();
        if (!context.isFolder() || context == rootFolder) {
            context = categoryOf(selectedLeafKind());
        }
        String name = JOptionPane.showInputDialog(this, "フォルダ名:");
        if (name == null) {
            return;
        }
        name = name.trim();
        if (name.isEmpty()) {
            return;
        }
        context.children().add(BookmarkNode.folder(name));
        rebuildTree();
        onChanged.run();
    }

    private BookmarkNode selectedFolder() {
        TreePath selected = tree.getSelectionPath();
        if (selected == null) {
            return categoryOf(BookmarkNode.Kind.PATH);
        }
        Object last = selected.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode treeNode)) {
            return categoryOf(BookmarkNode.Kind.PATH);
        }
        Object userObject = treeNode.getUserObject();
        if (userObject instanceof BookmarkNode node) {
            if (node.isFolder()) {
                return node;
            }
            BookmarkNode parent = findParent(rootFolder, node);
            return parent != null ? parent : categoryOf(BookmarkNode.Kind.PATH);
        }
        return categoryOf(BookmarkNode.Kind.PATH);
    }

    private BookmarkNode selectedFolderFor(BookmarkNode.Kind leafKind) {
        BookmarkNode selected = selectedFolder();
        BookmarkNode category = categoryOf(leafKind);
        if (isUnderCategory(selected, category)) {
            return selected.isFolder() ? selected : findParent(rootFolder, selected);
        }
        return category;
    }

    private BookmarkNode.Kind selectedLeafKind() {
        BookmarkNode node = selectedNode();
        if (node == null || node.isSystemCategory()) {
            return BookmarkNode.Kind.PATH;
        }
        if (node.isFolder()) {
            BookmarkNode category = findCategoryRoot(node);
            return category != null ? categoryKind(category) : BookmarkNode.Kind.PATH;
        }
        return node.kind();
    }

    private BookmarkNode selectedNode() {
        TreePath selected = tree.getSelectionPath();
        if (selected == null) {
            return null;
        }
        Object last = selected.getLastPathComponent();
        if (last instanceof DefaultMutableTreeNode treeNode
                && treeNode.getUserObject() instanceof BookmarkNode node) {
            return node;
        }
        return null;
    }

    private void renameSelected() {
        BookmarkNode node = selectedNode();
        if (node == null || node.isSystemCategory()) {
            return;
        }
        String current = node.isFolder() ? node.name() : node.displayText();
        String updated = JOptionPane.showInputDialog(this, "名前:", current);
        if (updated == null) {
            return;
        }
        updated = updated.trim();
        if (updated.isEmpty()) {
            return;
        }
        node.setName(updated);
        rebuildTree();
        onChanged.run();
    }

    private void moveSelectedUp() {
        moveSelectedByOffset(-1);
    }

    private void moveSelectedDown() {
        moveSelectedByOffset(1);
    }

    private void moveSelectedByOffset(int offset) {
        BookmarkNode node = selectedNode();
        if (node == null || node.isSystemCategory()) {
            return;
        }
        BookmarkNode parent = findParent(rootFolder, node);
        if (parent == null) {
            return;
        }
        List<BookmarkNode> siblings = parent.children();
        int index = siblings.indexOf(node);
        if (index < 0) {
            return;
        }
        int newIndex = index + offset;
        if (newIndex < 0 || newIndex >= siblings.size()) {
            return;
        }
        siblings.remove(index);
        siblings.add(newIndex, node);
        rebuildTree();
        selectNode(node);
        onChanged.run();
    }

    private void moveSelectedToFolder() {
        BookmarkNode node = selectedNode();
        if (node == null || node.isSystemCategory()) {
            return;
        }
        BookmarkNode category = findCategoryRoot(node);
        if (category == null) {
            return;
        }
        List<BookmarkNode> folders = new ArrayList<>();
        folders.add(category);
        collectFolders(category, folders, node);
        JComboBox<String> combo = new JComboBox<>();
        for (BookmarkNode folder : folders) {
            combo.addItem(folderDisplayName(folder));
        }
        combo.setSelectedItem(folderDisplayName(selectedFolder()));
        int answer = JOptionPane.showConfirmDialog(
                this,
                combo,
                "移動先フォルダ",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        int selectedIndex = combo.getSelectedIndex();
        if (selectedIndex < 0 || selectedIndex >= folders.size()) {
            return;
        }
        BookmarkNode targetFolder = folders.get(selectedIndex);
        moveNode(node, targetFolder, targetFolder.children().size());
    }

    private void deleteSelected() {
        BookmarkNode node = selectedNode();
        if (node == null || node.isSystemCategory()) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(
                this,
                "削除しますか？",
                "お気に入り",
                JOptionPane.YES_NO_OPTION
        );
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        removeNode(rootFolder, node);
        rebuildTree();
        onChanged.run();
    }

    private boolean removeNode(BookmarkNode parent, BookmarkNode target) {
        if (parent.children().remove(target)) {
            return true;
        }
        for (BookmarkNode child : parent.children()) {
            if (child.isFolder() && removeNode(child, target)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsPath(BookmarkNode parent, String path) {
        for (BookmarkNode child : parent.children()) {
            if (child.kind() == BookmarkNode.Kind.PATH && path.equals(child.path())) {
                return true;
            }
            if (child.isFolder() && containsPath(child, path)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsPathExcept(BookmarkNode parent, String path, BookmarkNode except) {
        for (BookmarkNode child : parent.children()) {
            if (child == except) {
                continue;
            }
            if (child.kind() == BookmarkNode.Kind.PATH && path.equals(child.path())) {
                return true;
            }
            if (child.isFolder() && containsPathExcept(child, path, except)) {
                return true;
            }
        }
        return false;
    }

    private BookmarkNode findParent(BookmarkNode parent, BookmarkNode target) {
        for (BookmarkNode child : parent.children()) {
            if (child == target) {
                return parent;
            }
            if (child.isFolder()) {
                BookmarkNode found = findParent(child, target);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean isDescendant(BookmarkNode ancestor, BookmarkNode node) {
        if (ancestor == node) {
            return true;
        }
        if (!ancestor.isFolder()) {
            return false;
        }
        for (BookmarkNode child : ancestor.children()) {
            if (isDescendant(child, node)) {
                return true;
            }
        }
        return false;
    }

    private void collectFolders(BookmarkNode parent, List<BookmarkNode> folders, BookmarkNode exclude) {
        for (BookmarkNode child : parent.children()) {
            if (!child.isFolder() || child == exclude || isDescendant(child, exclude)) {
                continue;
            }
            folders.add(child);
            collectFolders(child, folders, exclude);
        }
    }

    private String folderDisplayName(BookmarkNode folder) {
        if (folder.isSystemCategory()) {
            return folder.displayText();
        }
        return folder.displayText();
    }

    private boolean moveNode(BookmarkNode node, BookmarkNode targetParent, int insertIndex) {
        if (node == null || targetParent == null || !targetParent.isFolder() || node.isSystemCategory()) {
            return false;
        }
        if (node.isFolder() && isDescendant(node, targetParent)) {
            return false;
        }
        if (!sameCategory(node, targetParent)) {
            return false;
        }
        BookmarkNode currentParent = findParent(rootFolder, node);
        if (currentParent == null) {
            return false;
        }
        if (node.kind() == BookmarkNode.Kind.PATH && node.path() != null
                && containsPathExcept(categoryOf(BookmarkNode.Kind.PATH), node.path(), node)) {
            JOptionPane.showMessageDialog(this, "同じパスが既に存在します", "お気に入り", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        int currentIndex = currentParent.children().indexOf(node);
        if (currentParent == targetParent) {
            if (insertIndex > currentIndex) {
                insertIndex--;
            }
            if (insertIndex == currentIndex) {
                return false;
            }
        }
        currentParent.children().remove(node);
        insertIndex = Math.max(0, Math.min(insertIndex, targetParent.children().size()));
        targetParent.children().add(insertIndex, node);
        rebuildTree();
        selectNode(node);
        onChanged.run();
        return true;
    }

    private boolean sameCategory(BookmarkNode a, BookmarkNode b) {
        BookmarkNode categoryA = findCategoryRoot(a);
        BookmarkNode categoryB = findCategoryRoot(b);
        return categoryA != null && categoryA == categoryB;
    }

    private boolean isUnderCategory(BookmarkNode node, BookmarkNode category) {
        BookmarkNode root = findCategoryRoot(node);
        return root == category;
    }

    private BookmarkNode findCategoryRoot(BookmarkNode node) {
        if (node == null) {
            return null;
        }
        if (node.isSystemCategory()) {
            return node;
        }
        BookmarkNode parent = findParent(rootFolder, node);
        while (parent != null) {
            if (parent.isSystemCategory()) {
                return parent;
            }
            parent = findParent(rootFolder, parent);
        }
        return null;
    }

    private BookmarkNode categoryOf(BookmarkNode.Kind kind) {
        String name = switch (kind) {
            case SEARCH -> BookmarkNode.CATEGORY_SEARCH;
            case GREP -> BookmarkNode.CATEGORY_GREP;
            default -> BookmarkNode.CATEGORY_PATH;
        };
        for (BookmarkNode child : rootFolder.children()) {
            if (child.isSystemCategory() && name.equals(child.name())) {
                return child;
            }
        }
        BookmarkNode category = BookmarkNode.category(name);
        rootFolder.children().add(category);
        return category;
    }

    private BookmarkNode.Kind categoryKind(BookmarkNode category) {
        if (BookmarkNode.CATEGORY_SEARCH.equals(category.name())) {
            return BookmarkNode.Kind.SEARCH;
        }
        if (BookmarkNode.CATEGORY_GREP.equals(category.name())) {
            return BookmarkNode.Kind.GREP;
        }
        return BookmarkNode.Kind.PATH;
    }

    private void selectNode(BookmarkNode node) {
        DefaultMutableTreeNode treeNode = findTreeNode(rootTreeNode, node);
        if (treeNode != null) {
            TreePath path = new TreePath(treeNode.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        }
    }

    private DefaultMutableTreeNode findTreeNode(DefaultMutableTreeNode parent, BookmarkNode target) {
        if (parent.getUserObject() == target) {
            return parent;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            DefaultMutableTreeNode found = findTreeNode(child, target);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private DropTarget resolveDropTarget(JTree.DropLocation dropLocation) {
        TreePath destPath = dropLocation.getPath();
        if (destPath == null) {
            return null;
        }
        DefaultMutableTreeNode destTreeNode = (DefaultMutableTreeNode) destPath.getLastPathComponent();
        if (!(destTreeNode.getUserObject() instanceof BookmarkNode destNode)) {
            return null;
        }
        int childIndex = dropLocation.getChildIndex();
        if (childIndex == -1) {
            if (destNode.isFolder()) {
                return new DropTarget(destNode, destNode.children().size());
            }
            BookmarkNode parent = findParent(rootFolder, destNode);
            if (parent == null) {
                return null;
            }
            int index = parent.children().indexOf(destNode) + 1;
            return new DropTarget(parent, index);
        }
        BookmarkNode parent = destNode.isFolder() ? destNode : findParent(rootFolder, destNode);
        if (parent == null) {
            return null;
        }
        return new DropTarget(parent, childIndex);
    }

    private record DropTarget(BookmarkNode parent, int index) {}

    private void rebuildTree() {
        rootTreeNode.removeAllChildren();
        for (BookmarkNode child : rootFolder.children()) {
            rootTreeNode.add(toTreeNode(child));
        }
        treeModel.reload();
        expandAll();
    }

    private DefaultMutableTreeNode toTreeNode(BookmarkNode node) {
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
        if (node.isFolder()) {
            for (BookmarkNode child : node.children()) {
                treeNode.add(toTreeNode(child));
            }
        }
        return treeNode;
    }

    private void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    private static String defaultPathName(String pathText) {
        try {
            Path path = Paths.get(pathText.trim());
            Path fileName = path.getFileName();
            if (fileName != null) {
                return fileName.toString();
            }
        } catch (Exception ignored) {
        }
        String trimmed = pathText.trim();
        if (trimmed.endsWith("\\") || trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int slash = Math.max(trimmed.lastIndexOf('\\'), trimmed.lastIndexOf('/'));
        if (slash >= 0 && slash < trimmed.length() - 1) {
            return trimmed.substring(slash + 1);
        }
        return trimmed;
    }

    private void installTreeActions() {
        JMenuItem openItem = new JMenuItem("開く / 適用");
        openItem.addActionListener(e -> openSelected());
        JMenuItem addPathItem = new JMenuItem("パスを追加…");
        addPathItem.addActionListener(e -> promptAddPath(null));
        JMenuItem addSearchItem = new JMenuItem("現在の検索条件を追加");
        addSearchItem.addActionListener(e -> addCurrentSearch());
        JMenuItem addGrepItem = new JMenuItem("現在の Grep 条件を追加");
        addGrepItem.addActionListener(e -> addCurrentGrep());
        JMenuItem addFolderItem = new JMenuItem("フォルダを追加…");
        addFolderItem.addActionListener(e -> addFolder());
        JMenuItem moveUpItem = new JMenuItem("上へ");
        moveUpItem.addActionListener(e -> moveSelectedUp());
        JMenuItem moveDownItem = new JMenuItem("下へ");
        moveDownItem.addActionListener(e -> moveSelectedDown());
        JMenuItem moveToFolderItem = new JMenuItem("フォルダへ移動…");
        moveToFolderItem.addActionListener(e -> moveSelectedToFolder());
        JMenuItem renameItem = new JMenuItem("名前変更…");
        renameItem.addActionListener(e -> renameSelected());
        JMenuItem deleteItem = new JMenuItem("削除");
        deleteItem.addActionListener(e -> deleteSelected());

        popup.add(openItem);
        popup.addSeparator();
        popup.add(addPathItem);
        popup.add(addSearchItem);
        popup.add(addGrepItem);
        popup.add(addFolderItem);
        popup.addSeparator();
        popup.add(moveUpItem);
        popup.add(moveDownItem);
        popup.add(moveToFolderItem);
        popup.addSeparator();
        popup.add(renameItem);
        popup.add(deleteItem);

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelected();
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
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    tree.setSelectionPath(path);
                }
                updatePopupVisibility(openItem, addPathItem, addSearchItem, addGrepItem, addFolderItem,
                        moveUpItem, moveDownItem, moveToFolderItem, renameItem, deleteItem);
                popup.show(tree, e.getX(), e.getY());
            }
        });
    }

    private void updatePopupVisibility(JMenuItem openItem, JMenuItem addPathItem, JMenuItem addSearchItem,
                                       JMenuItem addGrepItem, JMenuItem addFolderItem, JMenuItem moveUpItem,
                                       JMenuItem moveDownItem, JMenuItem moveToFolderItem,
                                       JMenuItem renameItem, JMenuItem deleteItem) {
        BookmarkNode node = selectedNode();
        boolean categorySelected = node != null && node.isSystemCategory();
        boolean leafSelected = node != null && !node.isFolder();
        boolean mutableSelected = node != null && !node.isSystemCategory();

        openItem.setVisible(leafSelected);
        addPathItem.setVisible(categorySelected && BookmarkNode.CATEGORY_PATH.equals(node.name())
                || (node != null && findCategoryRoot(node) == categoryOf(BookmarkNode.Kind.PATH)));
        addSearchItem.setVisible(categorySelected && BookmarkNode.CATEGORY_SEARCH.equals(node.name())
                || (node != null && findCategoryRoot(node) == categoryOf(BookmarkNode.Kind.SEARCH)));
        addGrepItem.setVisible(categorySelected && BookmarkNode.CATEGORY_GREP.equals(node.name())
                || (node != null && findCategoryRoot(node) == categoryOf(BookmarkNode.Kind.GREP)));
        addFolderItem.setVisible(node == null || node.isFolder());
        moveUpItem.setVisible(mutableSelected);
        moveDownItem.setVisible(mutableSelected);
        moveToFolderItem.setVisible(mutableSelected);
        renameItem.setVisible(mutableSelected);
        deleteItem.setVisible(mutableSelected);

        if (node == null) {
            addPathItem.setVisible(true);
            addSearchItem.setVisible(true);
            addGrepItem.setVisible(true);
            addFolderItem.setVisible(false);
        }
    }

    private void openSelected() {
        BookmarkNode node = selectedNode();
        if (node == null || node.isFolder()) {
            return;
        }
        switch (node.kind()) {
            case PATH -> {
                if (node.path() != null && !node.path().isBlank()) {
                    onNavigate.accept(node.path());
                }
            }
            case SEARCH -> {
                applySearch.accept(new BookmarkSearchPreset(
                        node.searchPathPatterns(),
                        node.searchFilePatterns(),
                        node.searchExtensions()
                ));
                runSearch.run();
            }
            case GREP -> {
                applyGrep.accept(new BookmarkGrepPreset(
                        node.grepPattern(),
                        node.grepPath(),
                        node.grepFile(),
                        node.grepExtension(),
                        node.grepRegex(),
                        node.grepRecursive(),
                        node.grepContext()
                ));
                runGrep.run();
            }
            default -> {
            }
        }
    }

    private static final DataFlavor BOOKMARK_FLAVOR = new DataFlavor(BookmarkNode.class, "BookmarkNode");

    private final class BookmarkTransferHandler extends TransferHandler {

        private BookmarkNode draggedNode;

        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            draggedNode = selectedNode();
            if (draggedNode == null || draggedNode.isSystemCategory()) {
                return null;
            }
            return new BookmarkTransferable(draggedNode);
        }

        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            draggedNode = null;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (!support.isDrop() || draggedNode == null || draggedNode.isSystemCategory()) {
                return false;
            }
            JTree.DropLocation dropLocation = (JTree.DropLocation) support.getDropLocation();
            DropTarget target = resolveDropTarget(dropLocation);
            if (target == null) {
                return false;
            }
            if (draggedNode.isFolder() && isDescendant(draggedNode, target.parent())) {
                return false;
            }
            return sameCategory(draggedNode, target.parent())
                    && support.isDataFlavorSupported(BOOKMARK_FLAVOR);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (draggedNode == null) {
                return false;
            }
            JTree.DropLocation dropLocation = (JTree.DropLocation) support.getDropLocation();
            DropTarget target = resolveDropTarget(dropLocation);
            if (target == null) {
                return false;
            }
            return moveNode(draggedNode, target.parent(), target.index());
        }
    }

    private static final class BookmarkTransferable implements Transferable {

        private final BookmarkNode node;

        private BookmarkTransferable(BookmarkNode node) {
            this.node = node;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {BOOKMARK_FLAVOR};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return BOOKMARK_FLAVOR.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return node;
        }
    }

    private final class BookmarkTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(
                JTree tree,
                Object value,
                boolean selected,
                boolean expanded,
                boolean leaf,
                int row,
                boolean hasFocus) {
            Component component = super.getTreeCellRendererComponent(
                    tree, value, selected, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode treeNode
                    && treeNode.getUserObject() instanceof BookmarkNode node) {
                setText(node.displayText());
                if (node.isSystemCategory() || node.kind() == BookmarkNode.Kind.FOLDER) {
                    setIcon(UIManager.getIcon("FileView.directoryIcon"));
                    if (!selected) {
                        setForeground(FOLDER_COLOR);
                    }
                } else {
                    setIcon(UIManager.getIcon("FileView.fileIcon"));
                    if (!selected) {
                        setForeground(switch (node.kind()) {
                            case SEARCH -> SEARCH_COLOR;
                            case GREP -> GREP_COLOR;
                            default -> UIManager.getColor("Label.foreground");
                        });
                    }
                    setToolTipText(node.detailText());
                }
            }
            return component;
        }
    }
}
