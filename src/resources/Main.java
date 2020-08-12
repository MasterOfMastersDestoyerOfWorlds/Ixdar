package resources;
public class Main {
   public static void main(String argv[]) {
     System.out.println(System.mapLibraryName("qhull"));
     System.loadLibrary("qhull");
     System.out.println(qhull.qh_rand());
   }
 }
