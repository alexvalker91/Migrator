package alex.valker91.tm.model;

public record IosLocator(String strategy, String value) {
    public String toAnnotationAttribute() {
        return switch (strategy.toLowerCase()) {
            case "accessibility id" -> "accessibility";
            case "-ios class chain" -> "iOSClassChain";
            case "-ios predicate string" -> "iOSNsPredicate";
            default -> "xpath";
        };
    }
}
