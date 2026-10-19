package alex.valker91.tm.model;

public class TestStep {

    public String actionType;
    public String targetElementDescription;
    public String pageClassName;
    public String elementIdentifier;
    public String androidStrategy;
    public String androidSelector;
    public String inputValue;

    @Override
    public String toString() {
        return "[" + actionType + "] " + targetElementDescription + " (Target: " + pageClassName + "#" + elementIdentifier + ")";
    }
}
