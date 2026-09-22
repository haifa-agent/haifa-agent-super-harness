package io.haifa.agent.superharness.workspace;

/** Source values consumed by the frozen DeerFlow project-document client. */
public enum ProjectDocumentSourceKind {
    UPLOAD("upload"),
    OUTPUT("output");

    private final String wireValue;

    ProjectDocumentSourceKind(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
