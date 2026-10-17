package dev.trueseasons.ambience;

import java.util.List;
import java.util.function.Predicate;

/** One shared round-robin allowance, including unsuccessful habitat samples. */
public final class FireflyBudget {
    private FireflyBudget(){}
    public record Usage(int nextViewer,int attempts,int particles){}
    public static <T> Usage visit(List<T> viewers,int start,int attempts,int particles,Predicate<T> emit){
        if(attempts<1||attempts>64||particles<1||particles>64)throw new IllegalArgumentException("Invalid firefly work allowance");
        int used=0,shown=0,cursor=start;
        while(!viewers.isEmpty()&&used<attempts&&shown<particles){
            T viewer=viewers.get(Math.floorMod(cursor++,viewers.size()));used++;
            if(emit.test(viewer))shown++;
        }
        return new Usage(cursor,used,shown);
    }
}
