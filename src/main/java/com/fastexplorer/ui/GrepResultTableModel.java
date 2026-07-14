package com.fastexplorer.ui;

import com.fastexplorer.model.GrepMatch;
import com.fastexplorer.util.PathUtil;

import javax.swing.table.AbstractTableModel;
import java.nio.file.Path;
import java.util.List;

final class GrepResultTableModel extends AbstractTableModel {

    private static final String[] COLS = {"パス", "ファイル", "行", "内容"};
    private List<GrepMatch> matches = List.of();
    private Path displayBase;

    void setMatches(List<GrepMatch> matches, Path displayBase) {
        this.matches = matches;
        this.displayBase = displayBase;
        fireTableDataChanged();
    }

    GrepMatch getMatch(int row) {
        if (row < 0 || row >= matches.size()) {
            return null;
        }
        return matches.get(row);
    }

    List<GrepMatch> getMatches() {
        return matches;
    }

    int matchCount() {
        int count = 0;
        for (GrepMatch match : matches) {
            if (match.matched()) {
                count++;
            }
        }
        return count;
    }

    Path getDisplayBase() {
        return displayBase;
    }

    @Override
    public int getRowCount() {
        return matches.size();
    }

    @Override
    public int getColumnCount() {
        return COLS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLS[column];
    }

    @Override
    public Object getValueAt(int row, int column) {
        if (row < 0 || row >= matches.size()) {
            return "";
        }
        GrepMatch match = matches.get(row);
        return switch (column) {
            case 0 -> PathUtil.toDisplay(match.path());
            case 1 -> fileName(match);
            case 2 -> match.lineNumber() > 0 ? match.lineNumber() : "";
            case 3 -> formatLineContent(match);
            default -> "";
        };
    }

    private static String formatLineContent(GrepMatch match) {
        if (match.lineNumber() <= 0) {
            return "";
        }
        return (match.matched() ? ": " : "- ") + match.line();
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return columnIndex == 2 ? Integer.class : String.class;
    }

    private static String fileName(GrepMatch match) {
        Path fileName = match.path().getFileName();
        return fileName != null ? fileName.toString() : match.path().toString();
    }
}
