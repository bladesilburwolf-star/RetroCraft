package com.unrealcraft.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Imports the brush polygons in an Unreal text T3D export into the map mesh JSON format. */
public final class T3dImporter {
    private static final Pattern ACTOR=Pattern.compile("^Begin Actor Class=(\\S+) Name=(\\S+)");
    private static final Pattern LOCATION=vecPattern("Location");
    private static final Pattern PREPIVOT=vecPattern("PrePivot");
    private static final Pattern SCALE=Pattern.compile("(?:PostScale|MainScale|TempScale)=\\(Scale=\\(X=([-\\d.]+),Y=([-\\d.]+),Z=([-\\d.]+)");
    private static final Pattern VERTEX=Pattern.compile("Vertex\\s+([-+\\d.]+),([-+\\d.]+),([-+\\d.]+)");
    private T3dImporter(){}
    private static Pattern vecPattern(String name){return Pattern.compile(name+"=\\(X=([-\\d.]+),Y=([-\\d.]+),Z=([-\\d.]+)");}

    /** Writes a temporary companion mesh in raw Unreal units, which the normal map renderer scales. */
    public static Path importFile(Path input,float unitsPerBlock)throws IOException {
        String text=Files.readString(input, StandardCharsets.ISO_8859_1);
        String[] lines=text.split("\\R"); JsonArray triangles=new JsonArray();
        int i=0;
        while(i<lines.length){Matcher am=ACTOR.matcher(lines[i].trim());if(!am.find()){i++;continue;}
            String cls=am.group(1);List<String> block=new ArrayList<>();int depth=1;block.add(lines[i++]);
            while(i<lines.length&&depth>0){String line=lines[i++];String s=line.trim();block.add(line);if(s.startsWith("Begin "))depth++;else if(s.startsWith("End "))depth--;}
            if(!"Brush".equals(cls))continue;
            String whole=String.join("\n",block);float[] loc=vec(LOCATION,whole),pivot=vec(PREPIVOT,whole),scale={1,1,1};
            Matcher sm=SCALE.matcher(whole);while(sm.find())scale=new float[]{f(sm,1),f(sm,2),f(sm,3)};
            boolean inPoly=false;List<float[]> verts=new ArrayList<>();
            for(String line:block){String s=line.trim();if(s.startsWith("Begin Polygon")){inPoly=true;verts.clear();continue;}
                if(s.startsWith("End Polygon")){if(inPoly&&verts.size()>=3){List<float[]> world=new ArrayList<>();for(float[] v:verts)world.add(new float[]{(v[0]-pivot[0])*scale[0]+loc[0],(v[2]-pivot[2])*scale[2]+loc[2],-((v[1]-pivot[1])*scale[1]+loc[1])});
                    for(int k=1;k+1<world.size();k++){JsonArray tri=new JsonArray();add(tri,world.get(0));add(tri,world.get(k));add(tri,world.get(k+1));triangles.add(tri);}}
                    inPoly=false;continue;}
                if(inPoly){Matcher vm=VERTEX.matcher(s);if(vm.find())verts.add(new float[]{f(vm,1),f(vm,2),f(vm,3)});}
            }
        }
        if(triangles.isEmpty())throw new IOException("T3D has no brush polygon geometry");
        JsonObject root=new JsonObject();root.addProperty("format",1);root.addProperty("source","unreal_t3d_raw");root.addProperty("units_per_block",unitsPerBlock);root.add("triangles",triangles);
        Path cache=input.resolveSibling(input.getFileName()+".unrealcraft_mesh.json");
        Files.writeString(cache,new GsonBuilder().create().toJson(root),StandardCharsets.UTF_8);return cache;
    }
    private static float[] vec(Pattern p,String s){Matcher m=p.matcher(s);return m.find()?new float[]{f(m,1),f(m,2),f(m,3)}:new float[]{0,0,0};}
    private static float f(Matcher m,int i){return Float.parseFloat(m.group(i));}
    private static void add(JsonArray tri,float[] v){JsonArray p=new JsonArray();p.add(v[0]);p.add(v[1]);p.add(v[2]);tri.add(p);}
}
