package bigt;

/**
 * This class represents BigTable metadata
 * More fields can be added as required in the future
 */
public class BigTableInfo {
    private String name;

    public BigTableInfo(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
