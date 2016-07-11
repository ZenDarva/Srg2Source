package net.minecraftforge.srg2source.rangeapplier;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import net.minecraftforge.srg2source.rangeapplier.RangeMap.RangeEntry;
import net.minecraftforge.srg2source.util.Util;
import net.minecraftforge.srg2source.util.io.ConfLogger;
import net.minecraftforge.srg2source.util.io.FolderSupplier;
import net.minecraftforge.srg2source.util.io.InputSupplier;
import net.minecraftforge.srg2source.util.io.OutputSupplier;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.io.ByteStreams;

public class RangeApplier extends ConfLogger<RangeApplier>
{
    @SuppressWarnings({ "unchecked", "resource" })
    public static void main(String[] args) throws IOException
    {
        // configure parser
        OptionParser parser = new OptionParser();
        {
            parser.acceptsAll(Arrays.asList("h", "help")).isForHelp();
            parser.accepts("srcRoot", "Source root directory to rename").withRequiredArg().ofType(File.class).isRequired(); // var=srcRoot
            parser.accepts("srcRangeMap", "Source range map generated by srg2source").withRequiredArg().ofType(File.class).isRequired(); // var=srcRangeMap
            parser.accepts("srgFiles", "Symbol map file(s), separated by ' '").withRequiredArg().ofType(File.class).isRequired(); // var=srgFiles
            parser.accepts("git", "Command to invoke git"); // var=git default="git"
            parser.accepts("lvRangeMap", "Original source range map generated by srg2source, for renaming local variables"); // var=lvRangeMap)  CSV instead?
            parser.accepts("mcpConfDir", "MCP configuration directory, for renaming parameters").withRequiredArg().ofType(File.class); // var=mcpConfDir
            parser.accepts("excFiles", "Parameter map file(s), separated by ' '").withRequiredArg().ofType(File.class); // var=excFiles
            //parser.accepts("no-dumpRenameMap", "Disable dumping symbol rename map before renaming"); // var="dumpRenameMap", default=True
            parser.accepts("dumpRangeMap", "Enable dumping the ordered range map and quit"); // var=dumpRangeMap, default=False
            parser.accepts("outDir", "The output folder for editted classes.").withRequiredArg().ofType(File.class); // default null
        }

        OptionSet options = parser.parse(args);

        if (options.has("help"))
        {
            parser.printHelpOn(System.out);
            System.exit(0);
        }

        //boolean dumpRenameMap = !options.has("no-dumpRenameMap");
        boolean dumpRangeMap = options.has("dumpRangeMap");

        // read range map, spit, and return

        if (dumpRangeMap)
        {
            RangeMap ranges = new RangeMap().read((File) options.valueOf("srcRangeMap"));
            for (String key : ranges.keySet())
            {
                for (RangeEntry info : ranges.get(key))
                {
                    System.out.println(info);
                }
            }
            return;
        }

        // setup RangeApplier.
        RangeApplier app = new RangeApplier().readSrg((List<File>) options.valuesOf("srgFiles"));

        // read parameter remaps
        if (options.has("mcpConfDir") && options.hasArgument("mcpConfDir"))
        {
            File conf = (File) options.valueOf("mcpConfDir");
            File primary = new File(conf, "joined.exc");
            List<File> excs = new LinkedList<File>();

            if (!primary.exists())
                primary = new File(conf, "packaged.exc");

            excs.add(primary);

            if (options.has("excFiles") && options.hasArgument("excFiles"))
                excs.addAll((List<File>) options.valuesOf("excFiles"));

            app.readParamMap(excs);
        }

        // read local varaible map
        if (options.has("lvRangeMap") && options.hasArgument("lvRangeMap"))
        {
            app.readLvRangeMap((File) options.valueOf("lvRangeMap"));
        }

        //options.valuesOf("srgFiles");
        FolderSupplier srcRoot = new FolderSupplier((File) options.valueOf("srcRoot"));
        // output supplier..
        OutputSupplier outDir = (OutputSupplier) srcRoot;
        if (options.has("outDir"))
            outDir = new FolderSupplier((File) options.valueOf("outDir"));

        app.remapSources(srcRoot, outDir, (File) options.valueOf("srcRangeMap"), false);

        srcRoot.close();
        outDir.close();

        System.out.println("FINISHED!");
    }

    private SrgContainer    srg = new SrgContainer();
    private final RenameMap map = new RenameMap();
    private boolean keepImports = false; // Keep imports that are not referenced anywhere in code.

    // SRG stuff
    public RangeApplier readSrg(File srg)
    {
        this.srg.readSrg(srg);
        map.readSrg(this.srg);
        return this;
    }

    public RangeApplier readSrg(Iterable<File> srgs)
    {
        srg.readSrgs(srgs);
        map.readSrg(this.srg);
        return this;
    }

    public RangeApplier readSrg(SrgContainer srgCont)
    {
        this.srg = srgCont;
        map.readSrg(this.srg);
        return this;
    }

    // excptors
    public RangeApplier readParamMap(Iterable<File> exceptors)
    {
        ExceptorFile exc = new ExceptorFile().read(exceptors);
        map.readParamMap(srg, exc);

        return this;
    }

    public RangeApplier readParamMap(ExceptorFile exc)
    {
        map.readParamMap(srg, exc);

        return this;
    }

    // LV map
    public RangeApplier readLvRangeMap(File lvRangeMap)
    {
        try
        {
            map.readLocalVariableMap(new LocalVarFile().read(lvRangeMap), srg);
        }
        catch (IOException e)
        {
            Throwables.propagate(e);
        }

        return this;
    }

    /**
     * Outputs the contents of the rename map.
     * Spits everything to the outLogger.
     */
    public void dumpRenameMap()
    {
        List<Entry<String, String>> entries = new ArrayList<Entry<String, String>>(map.maps.entrySet());
        Collections.sort(entries, new Comparator<Entry<String, String>>()
        {
            @Override
            public int compare(Entry<String, String> o1, Entry<String, String> o2)
            {
                return o1.getKey().compareTo(o2.getKey());
            }
        });

        for (Entry<String, String> e : entries)
        {
            log("RENAME MAP: " + e.getKey() + " -> " + e.getValue());
        }
    }

    /**
     * Actually remaps stuff.
     * @param inSupp
     * @param outSupp
     * @param rangeMap
     * @param annotate Marks all renamed symbols with a comment and the old name.
     * @throws IOException
     */
    public void remapSources(InputSupplier inSupp, OutputSupplier outSupp, File rangeMap, boolean annotate) throws IOException
    {
        RangeMap range = new RangeMap().read(rangeMap);

        List<String> paths = new ArrayList<String>(range.keySet());
        Collections.sort(paths);

        for (String filePath : paths)
        {
            log("Start Processing: " + filePath);
            InputStream stream = inSupp.getInput(filePath);

            //no stream? what?
            if (stream == null)
            {
                // yeah.. nope.
                log("Data not found: " + filePath);
                continue;
            }

            String data = new String(ByteStreams.toByteArray(stream), Charset.forName("UTF-8"));
            stream.close();

            if (data.contains("\r"))
            {
                // to ensure that the offsets are not off by 1.
                log("Warning: " + filePath + " has CRLF line endings; consider switching to LF");
                data = data.replace("\r", "");
            }

            // process
            List<String> out = processJavaSourceFile(filePath, data, range.get(filePath), annotate);
            filePath = out.get(0);
            data = out.get(1);

            // write.
            OutputStream outStream = outSupp.getOutput(filePath);
            outStream.write(data.getBytes(Charset.forName("UTF-8")));
            outStream.close();

            log("End  Processing: " + filePath);
            log("");
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------------
    // ---------------------------------------------------------------------------------------------------

    /**
     * Rename symbols in source code
     * @return
     * @throws IOException
     */
    private ImmutableList<String> processJavaSourceFile(String fileName, String data, Collection<RangeEntry> rangeList, boolean shouldAnnotate) throws IOException
    {
        StringBuilder outData = new StringBuilder();
        outData.append(data);

        Set<String> importsToAdd = new TreeSet<String>();
        int shift = 0;

        // Existing package/class name (with package, internal) derived from filename
        String oldTopLevelClassFullName = Util.getTopLevelClassForFilename(fileName);
        String oldTopLevelClassPackage = Util.splitPackageName(oldTopLevelClassFullName);
        String oldTopLevelClassName = Util.splitBaseName(oldTopLevelClassFullName);

        // New package/class name through mapping
        String newTopLevelClassPackage = Util.sourceName2Internal(map.maps.get("package " + oldTopLevelClassPackage));
        String newTopLevelClassName = Util.splitBaseName(Util.sourceName2Internal(map.maps.get("class " + oldTopLevelClassFullName), false));
        if (newTopLevelClassPackage != null && newTopLevelClassName == null)
        {
            newTopLevelClassName = oldTopLevelClassName; // If the package changed, but we don't have a new name... assume we're keeping the name...
            // Could cause conflicts if the new package has a class with the same name!
            // We run into this for Minecraft's package-info.java's...
            //throw new RuntimeException("filename " + fileName + " found package " + oldTopLevelClassPackage + "->" + newTopLevelClassPackage + " but no class map for " + newTopLevelClassName);
        }
        if (newTopLevelClassPackage == null && newTopLevelClassName != null)
            throw new RuntimeException("filename " + fileName + " found class map " + oldTopLevelClassName + "->" + newTopLevelClassName + " but no package map for " + oldTopLevelClassPackage);

        if (newTopLevelClassPackage == null) // If the package wasnt remapped, then we're keeping our name!
        {
            newTopLevelClassPackage = oldTopLevelClassPackage;
            newTopLevelClassName = oldTopLevelClassName;
        }

        String newTopLevelQualifiedName = (newTopLevelClassPackage + "/" + newTopLevelClassName).replace('\\', '/');

        // start,end,expectedOldText,key
        for (RangeEntry info : rangeList)
        {
            int end = info.end;
            String expectedOldText = info.expectedOldText;
            if (map.maps.containsKey(info.key) && map.maps.get(info.key).isEmpty()) // has an empty key.
            {
                // Replacing a symbol with no text = removing a symbol
                if (!info.key.startsWith("package "))
                    throw new RuntimeException("unable to remove non-package symbol " + info.key);

                // Remove that pesky extra period after qualified package names
                end++;
                expectedOldText += ".";
            }

            String oldName = outData.substring(info.start + shift, end + shift);

            if (!oldName.equals(expectedOldText))
                throw new RuntimeException("Rename sanity check failed: expected '" + expectedOldText + "' at [" + info.start + "," + end + "] (shifted " + shift + " to [" + (shift + info.start) + "," + (shift + end) + "]) in " + fileName + ", but found '" + oldName + "'\nRegenerate symbol map on latest sources or start with fresh source and try again");

            String newName = getNewName(info.key, oldName, map.maps, shouldAnnotate);
            if (newName == null)
                newName = oldName;

            if (info.key.startsWith("class "))
            {
                // do importing.
                String key = info.key;
                if (key.equals("class " + newName.replace('.', '/')))
                {
                    key = null;
                }
                else if (newName.indexOf('.') > 0) // contains a .
                {
                    // split as many times as its qualified.
                    for (int i = 0; i < Util.countChar(newName, '.'); i++)
                        key = Util.splitPackageName(key);

                    log("New Key: "+key);
                }

                if (key == null)
                {
                    // No import, fully qualified! TOD: make this logic a bit better....
                }
                else
                {
                    String impt = key.substring(key.indexOf(' ') + 1);

                    if (map.imports.containsKey(key))
                        impt = map.imports.get(key).replace('.', '/').replace('$', '/'); // TODO: make extractor print in internal names so we know about inner classes, for now, convert to the same format as the key

                    if (needsImport(newTopLevelQualifiedName, impt))
                        importsToAdd.add(impt.replace('/', '.').replace('$', '.'));
                }
            }

            if (oldName.equals(newName))
                continue; //No rename? Skip the rest.

            log("Rename " + info.key + "[" + (info.start + shift) + "," + (end + shift) + "]" + "::" + oldName + "->" + newName);

            // Rename algorithm:
            // 1. textually replace text at specified range with new text
            // 2. shift future ranges by difference in text length
            //data = data.substring(0, info.start + shift) + newName + data.substring(end + shift);
            outData.replace(info.start + shift, end + shift, newName);
            shift += (newName.length() - oldName.length());
        }

        // Lastly, update imports - this == separate from symbol range manipulation above
        String outString = updateImports(outData, importsToAdd, map.imports);

        // rename?
        fileName = fileName.replace('\\', '/');
        String newFileName = newTopLevelQualifiedName + ".java";
        if (!fileName.equals(newFileName))
        {
            log("Rename file " + fileName + " -> " + newFileName);

            fileName = newFileName;
        }

        return ImmutableList.of(fileName, outString);
    }

    private boolean needsImport(String topLevel, String reference)
    {
        if (reference.startsWith(topLevel)) //This is a inner class, nested unknown amounts deep.... Just assume it's qualified correctly in code.
            return false;
        if (Util.splitPackageName(topLevel).equals(Util.splitPackageName(reference))) // We are in the same package, no import needed
            return false;
        //Keep java/lang for now, to keep the imports in the source if they are explicitly imported.
        //We remove the NEED for them before adding any.

        //This needs to be made better by taking into account inheratance, but I don't know of a simple way to hack inheratance into this,
        //so I think we're gunna have to live with some false positives. We just have to be careful when patching.

        //log("CheckImport: " + topLevel);
        //log("             " + reference);
        return true;
    }

    /**
     * Add new import statements to source
     */
    private String updateImports(StringBuilder data, Set<String> newImports, Map<String, String> importMap)
    {
        //String[] lines = data.split("\n");
        if (data.charAt(data.length()-1) != '\n')
            data.append('\n');

        int lastIndex = 0;
        int nextIndex = data.indexOf("\n");
        // Parse the existing imports and find out where to add ours
        // This doesn't use Psi.. but the syntax is easy enough to parse here
        boolean addedNewImports = false;
        boolean sawImports = false;
        int packageLine = -1;

        String line;
        while (nextIndex > -1)
        {
            line = data.substring(lastIndex, nextIndex);

            while (line.isEmpty() || line.startsWith("\n"))
            {
                lastIndex++;
                nextIndex = data.indexOf("\n", lastIndex + 1);
                line = data.substring(lastIndex, nextIndex);
            }
            //log("Line: " + line);

            if (line.startsWith("package "))
                packageLine = nextIndex + 1;

            if (line.startsWith("import "))
            {
                sawImports = true;

                // remove stuff thats already added by a wildcard
                if (line.indexOf('*') > 0)
                {
                    LinkedList<String> remove = new LinkedList<String>();
                    String starter = line.replace("import ", "").replace(".*;", "").trim();
                    for (String imp : newImports)
                    {
                        String impStart = imp.substring(0, imp.lastIndexOf('.'));
                        if (impStart.equals(starter))
                            remove.add(imp);
                    }
                    newImports.removeAll(remove);
                }

                String oldClass = line.replace("import ", "").replace(";", "").trim();

                String newClass = importMap.get("class " + Util.sourceName2Internal(oldClass));
                if (newClass == null)
                    newClass = oldClass;
                newClass = newClass.replace('$', '.');

                log("Import: " + newClass);

                if (!newImports.remove(newClass)) // New file doesn't need the import, so delete the line.
                {
                    //log("        " + HashCode.fromBytes(newClass.getBytes()).toString());
                    if (this.keepImports)
                        lastIndex = nextIndex + 1;
                    else
                        data.delete(lastIndex, nextIndex + 1);
                    nextIndex = data.indexOf("\n", lastIndex);
                    continue;
                }

                if (!oldClass.equals(newClass)) // Got renamed
                {
                    int change = "import ".length();
                    data.replace(lastIndex, nextIndex, "import ");
                    data.insert(lastIndex + change, newClass);
                    change += newClass.length();
                    data.insert(lastIndex + change, ";");
                    nextIndex = lastIndex + change + 1; // +1 for the semicolon
                }
            }
            else if (sawImports && !addedNewImports)
            {
                filterImports(newImports);

                if (newImports.size() > 0)
                {
                    // Add our new imports right after the last import
                    CharSequence sub = data.subSequence(lastIndex, data.length()); // grab the rest of the string.
                    data.setLength(lastIndex); // cut off the build there

                    for (String imp : newImports)
                        data.append("import ").append(imp).append(";\n");

                    if (newImports.size() > 0)
                        data.append('\n');

                    int change = data.length() - lastIndex; // get changed size
                    lastIndex = data.length(); // reset the end to the actual end..
                    nextIndex += change; // shift nextIndex accordingly..

                    data.append(sub); // add on the rest if the string again
                }

                addedNewImports = true;
                break; //We've added out imports lets exit.
            }

            // next line.
            lastIndex = nextIndex + 1; // +1 to skip the \n at the end of the line there
            nextIndex = data.indexOf("\n", lastIndex + 1); // another +1 because otherwise it would just return lastIndex
        }

        // got through the whole file without seeing or adding any imports???
        if (!addedNewImports)
        {
            filterImports(newImports);

            if (newImports.size() > 0)
            {
                //If we saw the package line, add to it after that.
                //If not prepend to the start of the file
                int index = packageLine == -1 ? 0 : packageLine;

                CharSequence sub = data.subSequence(index, data.length()); // grab the rest of the string.
                data.setLength(index); // cut off the build there

                for (String imp : newImports)
                    data.append("import ").append(imp).append(";\n");

                if (newImports.size() > 0)
                    data.append('\n');

                data.append(sub); // add on the rest if the string again
            }
        }

        return data.toString();
    }

    private void filterImports(Set<String> newImports)
    {
        Iterator<String> itr  = newImports.iterator();
        while (itr.hasNext())
        {
            if (itr.next().startsWith("java.lang.")) //java.lang classes can be referenced without imports
                itr.remove();                        //We remove them here to allow for them to exist in src
                                                     //But we will never ADD them
        }

        if (newImports.size() > 0)
        {
            log("Adding " + newImports.size() + " imports");
            for (String imp : newImports)
            {
                log("        " + imp);
                //log("        " + HashCode.fromBytes(imp.getBytes()).toString());
            }
        }
    }

    private String getNewName(String key, String oldName, Map<String, String> renameMap, boolean shouldAnnotate)
    {
        String newName;
        if (!renameMap.containsKey(key))
        {
            String constructorClassName = getConstructor(key);
            if (constructorClassName != null)
            {
                // Constructors are not in the method map (from .srg, and can't be derived
                // exclusively from the class map since we don't know all the parameters).. so we
                // have to synthesize a rename from the class map here. Ugh..but, it works.
                log("FOUND CONSTR " + key + " " + constructorClassName);
                if (renameMap.containsKey("class " + constructorClassName))
                    // Rename constructor to new class name
                    newName = Util.splitBaseName(Util.sourceName2Internal(renameMap.get("class " + constructorClassName), false));
                else
                    return null;
            }
            else
                // Not renaming this
                return null;
        }
        else
            newName = renameMap.get(key);

        newName = Util.splitBaseName(newName, Util.countChar(oldName, '.'));

        if (shouldAnnotate)
            newName += "/* was " + oldName + "*/";

        return newName;
    }

    /**
     * Check whether a unique identifier method key is a constructor, if so return full class name for remapping, else null
     */
    private String getConstructor(String key)
    {
        String[] tokens = key.split(" ", 3);  // TODO: switch to non-conflicting separator..types can have spaces :(
        if (!tokens[0].equals("method"))
            return null;
        //log(Arrays.toString(tokens));
        //kind, fullMethodName, methodSig = tokens
        if (tokens[2].charAt(tokens[2].length() - 1) != 'V') // constructors marked with 'V' return type signature in ApplySrg2Source and MCP
            return null;
        String fullClassName = Util.splitPackageName(tokens[1]);
        String methodName = Util.splitBaseName(tokens[1]);

        String className = Util.splitBaseName(fullClassName);

        if (className.equals(methodName)) // constructor has same name as class
            return fullClassName;
        else
            return null;
    }

    public void setKeepImports(boolean value)
    {
        this.keepImports = value;
    }
}
