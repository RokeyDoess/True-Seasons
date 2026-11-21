package dev.trueseasons.snow;

import java.util.*;

public final class PreviewSchedulingTests {
    private static int checks;
    private record ReferencePosition(UUID world,int x,int z){}
    public static void main(String[] args)throws Exception{
        positionKeys();
        UUID first=new UUID(0,1),second=new UUID(0,2);
        var queue=new PreviewPriorityQueue<Integer>(128);
        for(int i=0;i<100;i++)check(queue.offer(first,i),"First viewer can queue a join burst");
        check(queue.offer(second,1000)&&!queue.offer(second,0),"Fresh work is deduplicated across viewers");
        check(queue.poll()==0&&queue.poll()==1000,"A later viewer receives a turn before the first viewer's burst drains");
        queue.retain(value->value>=90);queue.removeViewer(first);check(queue.isEmpty(),"Invisible and disconnected work leaves no queued ownership");
        for(int round=0;round<4;round++)for(int viewer=0;viewer<20;viewer++)queue.offer(new UUID(0,viewer),round*100+viewer);
        for(int round=0;round<4;round++)for(int viewer=0;viewer<20;viewer++)check(queue.poll()==round*100+viewer,"Each viewer receives one turn per round");
        check(queue.isEmpty(),"Every fresh chunk is visited exactly once");
        var small=new PreviewPriorityQueue<String>(2);
        check(small.offer(first,"a")&&small.offer(first,"b")&&!small.offer(second,"c"),"Fresh visibility queues have a fixed capacity");
        check(small.poll().equals("a")&&small.offer(second,"c"),"Processing releases capacity for another viewer");
        small.removeViewer(first);check(small.poll().equals("c")&&small.isEmpty(),"Removing a viewer preserves another viewer's pending chunk");
        queue.offer(first,1);queue.clear();check(queue.isEmpty()&&queue.offer(second,1),"Season reset releases both deduplication and viewer turns");
        var near=new PreviewPriorityQueue<String>(8);
        near.offer(first,"far",10);near.offer(first,"near",1);near.offer(first,"standing",0);near.offer(second,"other",0);
        check(near.poll().equals("standing")&&near.poll().equals("other")&&near.poll().equals("near"),"join catch-up starts beside each viewer before distant chunks while retaining viewer turns");
        check(near.offer(first,"far",0)&&!near.offer(first,"far",8),"a pending chunk can be promoted after movement without duplicates or priority regression");
        check(near.size()==1&&near.poll().equals("far")&&near.isEmpty(),"priority promotion retains one work item and releases its capacity exactly once");
        for(int i=400;i>=0;i--)near.offer(first,"chunk-"+i,i);
        near.removeViewer(first);check(near.isEmpty(),"a bounded priority burst leaves no retained work after disconnect");
        System.out.println("Preview scheduling tests passed: "+checks+" assertions");
    }
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void positionKeys()throws Exception{
        var constructor=Class.forName("dev.trueseasons.snow.LocalWinterSnowService$Position").getDeclaredConstructor(UUID.class,int.class,int.class);
        constructor.setAccessible(true);var random=new Random(6271);
        for(int i=0;i<4096;i++){
            UUID world=i%11==0?null:new UUID(random.nextLong(),random.nextLong());
            int x=i==0?Integer.MIN_VALUE:random.nextInt(),z=i==1?Integer.MAX_VALUE:random.nextInt();
            Object key=constructor.newInstance(world,x,z),equal=constructor.newInstance(world==null?null:new UUID(world.getMostSignificantBits(),world.getLeastSignificantBits()),x,z);
            check(key.hashCode()==new ReferencePosition(world,x,z).hashCode(),"Preview key hashes retain their previous coordinate distribution");
            check(key.equals(equal)&&equal.equals(key)&&!key.equals(null)&&!key.equals(new ReferencePosition(world,x,z)),"Preview keys keep value equality within their own record type");
            check(!key.equals(constructor.newInstance(world,x^1,z))&&!key.equals(constructor.newInstance(world,x,z^1))&&!key.equals(constructor.newInstance(new UUID(7,11),x,z)),"Other worlds and coordinates cannot share preview work");
            var shared=new PreviewPriorityQueue<Object>(2);UUID viewer=new UUID(0,1);
            check(shared.offer(viewer,key)&&!shared.offer(new UUID(0,2),equal)&&shared.size()==1&&shared.poll().equals(equal)&&shared.isEmpty(),"Equivalent keys from different viewers share one queue entry and release it once");
        }
    }
}
