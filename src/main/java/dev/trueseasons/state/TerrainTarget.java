package dev.trueseasons.state;

import java.util.Objects;

/** A terrain checkpoint changes only when the required world edits change. */
public record TerrainTarget(String version, boolean winter, String rules, long generation) {
    public TerrainTarget {
        Objects.requireNonNull(version); Objects.requireNonNull(rules);
        if(version.isBlank()||generation<0)throw new IllegalArgumentException("Invalid terrain checkpoint");
    }
    @Override public boolean equals(Object other){return this==other||other instanceof TerrainTarget target&&winter==target.winter&&generation==target.generation&&version.equals(target.version)&&rules.equals(target.rules);}
    @Override public int hashCode(){return Objects.hash(version,winter,rules,generation);}
    public TerrainTarget(String version,boolean winter,String rules){this(version,winter,rules,0);}
    public static TerrainTarget resolve(TerrainTarget saved, String logicalVersion, boolean winter, String rules) {
        if (saved != null && saved.winter == winter && saved.rules.equals(rules)) return saved;
        long generation=saved==null?0:Math.incrementExact(saved.generation);
        return new TerrainTarget(logicalVersion+"-t"+generation,winter,rules,generation);
    }
}
