/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.naming.resources;

import static org.jboss.web.NamingMessages.MESSAGES;

import java.io.File;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.OperationNotSupportedException;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;

/**
 * Alias based implementation.
 *
 * @author Remy Maucherat
 * @author Mark Thomas
 */
public class AliasDirContext extends BaseDirContext {

    protected Map<String, DirContext> aliases = new ConcurrentHashMap<String, DirContext>();
    
    public AliasDirContext() {
        super();
    }

    public AliasDirContext(Hashtable env) {
        super(env);
    }

    /**
     * Add an alias.
     */
    public void addAlias(String path, BaseDirContext dirContext) {
        if (!path.startsWith("/")) {
            throw MESSAGES.invalidAliasPath(path);
        }
        aliases.put(path, dirContext);
    }

    
    /**
     * Remove an alias.
     */
    public void removeAlias(String path) {
        if (!path.startsWith("/")) {
            throw MESSAGES.invalidAliasPath(path);
        }
        aliases.remove(path);
    }
    
    
    /**
     * Get the current alias configuration in String form. If no aliases are
     * configured, an empty string will be returned.
     */
    public String getAliases() {
        StringBuilder result = new StringBuilder();
        Iterator<Entry<String,DirContext>> iter =
            aliases.entrySet().iterator();
        boolean first = true;
        while (iter.hasNext()) {
            if (first) {
                first = false;
            } else {
                result.append(',');
            }
            Entry<String,DirContext> entry = iter.next();
            result.append(entry.getKey());
            result.append('=');
            if (entry.getValue() instanceof BaseDirContext) {
                result.append(((BaseDirContext) entry.getValue()).getDocBase());
            }
        }
        return result.toString();
    }

    
    /**
     * Set the current alias configuration from a String. The String should be
     * of the form "/aliasPath1=docBase1,/aliasPath2=docBase2" where aliasPathN
     * must include a leading '/' and docBaseN must be an absolute path to
     * either a .war file or a directory. Any call to this method will replace
     * the current set of aliases.
     */
    public void setAliases(String theAliases) {
        // Overwrite whatever is currently set
        aliases.clear();
        
        if (theAliases == null || theAliases.length() == 0)
            return;
        
        String[] kvps = theAliases.split(",");
        for (String kvp : kvps) {
            String[] kv = kvp.split("=");
            if (kv.length != 2 || kv[0].length() == 0 || kv[1].length() == 0)
                throw MESSAGES.invalidAliasMapping(kvp);
            File aliasLoc = new File(kv[1]);
            if (!aliasLoc.exists()) {
                throw MESSAGES.aliasNotFound(kv[1]);
            }
            BaseDirContext context;
            if (kv[1].endsWith(".war") && !(aliasLoc.isDirectory())) {
                context = new WARDirContext();
            } else if (aliasLoc.isDirectory()) {
                context = new FileDirContext();
            } else {
                throw MESSAGES.aliasNotFolder(kv[1]);
            }
            context.setDocBase(kv[1]);
            addAlias(kv[0], context);
        }
    }

    
    private AliasResult findAlias(String name) {
        AliasResult result = new AliasResult();
        
        String searchName = name;
        
        result.dirContext = aliases.get(searchName);
        while (result.dirContext == null) {
            int slash = searchName.lastIndexOf('/');
            if (slash < 0)
                break;
            searchName = searchName.substring(0, slash);
            result.dirContext = aliases.get(searchName);
        }
        
        if (result.dirContext != null)
            result.aliasName = name.substring(searchName.length());
        
        return result;
    }

    private static class AliasResult {
        DirContext dirContext;
        String aliasName;
    }

    public void release() {
        this.aliases.clear();
        super.release();
    }

    public Object lookup(String name) throws NamingException {
        AliasResult result = findAlias(name);
        if (result.dirContext != null) {
            return result.dirContext.lookup(result.aliasName);
        }
        throw new NameNotFoundException(MESSAGES.resourceNotFound(name));
    }

    public Object lookup(Name name) throws NamingException {
        return lookup(name.toString());
    }

    public void unbind(String name) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public void rename(String oldName, String newName) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public NamingEnumeration list(String name) throws NamingException {
        AliasResult result = findAlias(name);
        if (result.dirContext != null) {
            return result.dirContext.list(result.aliasName);
        }
        throw new NameNotFoundException(MESSAGES.resourceNotFound(name));
    }

    public NamingEnumeration list(Name name) throws NamingException {
        return list(name.toString());
    }

    public NamingEnumeration listBindings(String name) throws NamingException {
        AliasResult result = findAlias(name);
        if (result.dirContext != null) {
            return result.dirContext.listBindings(result.aliasName);
        }
        throw new NameNotFoundException(MESSAGES.resourceNotFound(name));
    }

    public NamingEnumeration listBindings(Name name) throws NamingException {
        return listBindings(name.toString());
    }

    public void destroySubcontext(String name) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public Object lookupLink(String name) throws NamingException {
        // Note : Links are not supported
        return lookup(name);
    }

    public String getNameInNamespace() throws NamingException {
        return docBase;
    }

    public Attributes getAttributes(String name, String[] attrIds) throws NamingException {
        AliasResult result = findAlias(name);
        if (result.dirContext != null) {
            return result.dirContext.getAttributes(result.aliasName, attrIds);
        }
        throw new NameNotFoundException(MESSAGES.resourceNotFound(name));
    }

    public Attributes getAttributes(Name name, String[] attrIds) throws NamingException {
        return getAttributes(name.toString(), attrIds);
    }

    public void modifyAttributes(String name, int mod_op, Attributes attrs) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public void modifyAttributes(String name, ModificationItem[] mods) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public void bind(String name, Object obj, Attributes attrs) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public void rebind(String name, Object obj, Attributes attrs) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public DirContext createSubcontext(String name, Attributes attrs) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public DirContext getSchema(String name) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public DirContext getSchemaClassDefinition(String name) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public NamingEnumeration search(String name, Attributes matchingAttributes, String[] attributesToReturn)
            throws NamingException {
        throw new OperationNotSupportedException();
    }

    public NamingEnumeration search(String name, Attributes matchingAttributes) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public NamingEnumeration search(String name, String filter, SearchControls cons) throws NamingException {
        throw new OperationNotSupportedException();
    }

    public NamingEnumeration search(String name, String filterExpr, Object[] filterArgs, SearchControls cons)
            throws NamingException {
        throw new OperationNotSupportedException();
    }

}
