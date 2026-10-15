package dev.trueseasons.snow;

import java.util.*;

public final class PreviewSchedulingTests {
    private static int checks;
    public static void main(String[] args){
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
        System.out.println("Preview scheduling tests passed: "+checks+" assertions");
    }
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
}
