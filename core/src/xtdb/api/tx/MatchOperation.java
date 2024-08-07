package xtdb.api.tx;

import xtdb.api.XtdbDocument;

import java.util.Date;
import java.util.Objects;

public final class MatchOperation extends TransactionOperation {
    public static MatchOperation create(Object id) {
        return new MatchOperation(id, null, null);
    }

    public static MatchOperation create(Object id, Date atValidTime) {
        return new MatchOperation(id, null, atValidTime);
    }

    public static MatchOperation create(XtdbDocument document) {
        return new MatchOperation(document.getId(), document, null);
    }

    public static MatchOperation create(XtdbDocument document, Date atValidTime) {
        return new MatchOperation(document.getId(), document, atValidTime);
    }

    public Object getId() {
        return id;
    }

    public XtdbDocument getDocument() {
        return document;
    }

    public Date getAtValidTime() {
        return atValidTime;
    }

    private final Object id;
    private final XtdbDocument document;
    private final Date atValidTime;

    private MatchOperation(Object id, XtdbDocument document, Date atValidTime) {
        this.id = id;
        this.document = document;
        this.atValidTime = atValidTime;
    }

    @Override
    public <E> E accept(Visitor<E> visitor) {
        return visitor.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MatchOperation that = (MatchOperation) o;
        return id.equals(that.id)
                && Objects.equals(document, that.document)
                && Objects.equals(atValidTime, that.atValidTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash("match", id, document, atValidTime);
    }
}
