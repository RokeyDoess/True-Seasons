package dev.trueseasons.presentation;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.trueseasons.config.SnowPresentationLimits;
import org.bukkit.configuration.file.YamlConfiguration;

public final class SnowPresentationBudgetTests {
    private static int checks;
    public static void main(String[] args)throws Exception{
        configuration();reconfiguration();
        var budget=new SnowPresentationBudget(8,1024);
        check(budget.reserve(8,1024)==null,"No work is admitted before a server frame starts");budget.beginTick(1);
        var first=budget.reserve(8,1024);check(first!=null&&budget.reserve(1,1)==null,"A reservation cannot oversubscribe shared blocks or chunks");
        budget.beginTick(1);check(budget.reserve(1,1)==null,"Repeated presentation calls in the same tick do not reset the pool");
        check(first.begin()&&!first.begin(),"A grant starts at most once");first.complete(2,100);first.close();
        var remainder=budget.reserve(8,1024);check(remainder.chunks==6&&remainder.blocks==924,"Unused capacity is returned exactly once");remainder.close();
        var expired=budget.reserve(8,1024);budget.beginTick(2);check(!expired.begin(),"Queued grants expire before starting in a newer tick");expired.close();
        var full=budget.reserve(8,1024);check(full.chunks==8&&full.blocks==1024,"Expired refunds cannot inflate the new frame");
        check(full.begin(),"Current work is admitted");full.complete(8,0);
        check(budget.reserve(1,1)==null,"No-op chunk scans still consume the shared chunk budget");
        budget.beginTick(3);check(budget.usage().chunks()==8&&budget.usage().blocks()==0,"Diagnostic counters retain no-op visits without inventing block changes");
        var cancel=budget.reserve(8,1024);cancel.close();check(budget.reserve(8,1024)!=null,"Canceled work returns capacity before it begins");
        budget.beginTick(4);var late=budget.reserve(2,64);check(late.begin(),"Current work starts");budget.beginTick(5);late.complete(1,30);late.close();
        var fresh=budget.reserve(8,1024);check(fresh.chunks==8&&fresh.blocks==1024,"Late completions never refund another tick's pool");fresh.close();
        budget.beginTick(6);check(budget.usage().chunks()==1&&budget.usage().blocks()==30,"Diagnostics count delayed work when it actually completes");
        var pool=Executors.newFixedThreadPool(8);
        try{
            for(int frame=0;frame<40;frame++){
                budget.beginTick(100+frame);AtomicInteger blocks=new AtomicInteger(),chunks=new AtomicInteger();List<Future<?>> futures=new ArrayList<>();
                for(int viewer=0;viewer<128;viewer++)futures.add(pool.submit(()->{
                    var grant=budget.reserve(1,32);if(grant==null)return;
                    try(grant){if(grant.begin()){chunks.addAndGet(grant.chunks);blocks.addAndGet(grant.blocks);grant.complete(grant.chunks,grant.blocks);}}
                }));
                for(var future:futures)future.get();check(chunks.get()==8&&blocks.get()==256,"Concurrent viewers share one chunk pool without overspending");
            }
        }finally{pool.shutdownNow();}
        System.out.println("Snow presentation budget tests passed: "+checks+" assertions");
    }
    private static void configuration()throws Exception{
        var yaml=new YamlConfiguration();
        check(SnowPresentationLimits.read(yaml).equals(new SnowPresentationLimits(64,8192)),"Older configurations retain the existing shared defaults");
        yaml.loadFromString("presentation:\n  max-chunks-per-tick: 3\n  max-block-updates-per-tick: 19\n");
        check(SnowPresentationLimits.read(yaml).equals(new SnowPresentationLimits(3,19)),"Both configured limits are read independently");
        yaml.set("presentation.max-block-updates-per-tick",null);
        check(SnowPresentationLimits.read(yaml).equals(new SnowPresentationLimits(3,8192)),"Missing options gain defaults without replacing custom values");
        String[] keys={"max-chunks-per-tick","max-block-updates-per-tick"};int[] maximums={1024,131072};
        for(int index=0;index<keys.length;index++){
            String path="presentation."+keys[index];yaml=new YamlConfiguration();
            for(Object bad:new Object[]{0,-1,maximums[index]+1,Long.MAX_VALUE,1.5,"8",true,List.of(8)}){
                yaml.set(path,bad);
                try{SnowPresentationLimits.read(yaml);throw new AssertionError("Accepted invalid limit: "+bad);}
                catch(IllegalArgumentException expected){check(expected.getMessage().contains(path),"Invalid budgets identify the snow.yml option");}
            }
            for(int good:new int[]{1,maximums[index]}){yaml.set(path,good);SnowPresentationLimits.read(yaml);check(true,"Both documented boundaries are accepted");}
        }
    }
    private static void reconfiguration(){
        var budget=new SnowPresentationBudget();budget.beginTick(1);
        var old=budget.reserve(64,8192);check(old.begin(),"Work may be in flight during reload");
        budget.limits(new SnowPresentationLimits(1,7));budget.beginTick(1);
        check(budget.reserve(1,1)==null,"Reload cannot reopen the current tick's allowance");
        old.complete(63,8190);var returned=budget.reserve(8,1024);
        check(returned.chunks==1&&returned.blocks==2,"In-flight refunds stay within the old frame allowance");returned.close();
        budget.beginTick(2);var smaller=budget.reserve(8,1024);
        check(smaller.chunks==1&&smaller.blocks==7,"Lower configured limits apply at the next tick");
        check(smaller.begin(),"Lower-limit work begins");
        budget.limits(new SnowPresentationLimits(10,2000));budget.beginTick(2);
        check(budget.reserve(1,1)==null,"Raising limits also waits for the next tick");
        budget.beginTick(3);smaller.complete(0,0);var larger=budget.reserve(100,10000);
        check(larger.chunks==10&&larger.blocks==2000,"Late old-limit completions cannot inflate the new allowance");larger.close();
        var queued=budget.reserve(10,2000);budget.limits(new SnowPresentationLimits(1,1));budget.beginTick(4);
        check(!queued.begin(),"A queued grant from before reload expires at the tick boundary");queued.close();
        var minimum=budget.reserve(8,1024);check(minimum.chunks==1&&minimum.blocks==1,"The smallest supported allowance stays exact");minimum.close();
    }
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
}
