/*
 This file is part of the BlueJ program. 
 Copyright (C) 1999-2009  Michael K�lling and John Rosenberg 
 
 This program is free software; you can redistribute it and/or 
 modify it under the terms of the GNU General Public License 
 as published by the Free Software Foundation; either version 2 
 of the License, or (at your option) any later version. 
 
 This program is distributed in the hope that it will be useful, 
 but WITHOUT ANY WARRANTY; without even the implied warranty of 
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the 
 GNU General Public License for more details. 
 
 You should have received a copy of the GNU General Public License 
 along with this program; if not, write to the Free Software 
 Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA. 
 
 This file is subject to the Classpath exception as provided in the  
 LICENSE.txt file that accompanied this code.
 */
package bluej.runtime;

import java.util.Map;

/**
 * Interface implemented by all "shell" classes.
 *
 * The src for each "shell" class is constructed automatically,
 * compiled and executed. They are used for method invocation
 * and object creation.
 *
 * @author  Michael Cahill
 * @author  Michael Kolling
 * @version $Id: Shell.java 6164 2009-02-19 18:11:32Z polle $
 */
public abstract class Shell
{
    /**
     * A dummy method called by class loader to prepare the class
     * after loading.
     */
    public static void prepare()
    {
    }

    /**
     * Provide the shell class with static access to the object
     * bench scopes.
     */
    protected static Map getScope(String scopeId)
    {
        return ExecServer.getScope(scopeId);
    }

    /**
     * Put an object into the scope of one of the object benches.
     */
    protected static void putObject(String scopeId, String instanceName, Object value)
    {
        ExecServer.addObject(scopeId, instanceName, value);
    }

    /**
     * Construct an object that allows the result to be plucked out by
     * the debugger.
     *
     * Object results are different from all the primitive types such as
     * boolean, byte etc.. don't exactly know why but it is..
     */
    public static ObjectResultWrapper makeObj(Object o) {
        return new ObjectResultWrapper(o);
    }

    /**
     * Construct an object that allows the result to be plucked out by
     * the debugger.
     */
    protected static Object makeObj(final String s) {
        return new Object() {
           public String result = s;
        };
    }

    protected static Object makeObj(final boolean b) {
        return new Object() {
           public boolean result = b;
        };
    }

    protected static Object makeObj(final byte b) {
        return new Object() {
           public byte result = b;
        };
    }

    protected static Object makeObj(final char c) {
        return new Object() {
           public char result = c;
        };
    }

    protected static Object makeObj(final double d) {
        return new Object() {
           public double result = d;
        };
    }

    protected static Object makeObj(final float f) {
        return new Object() {
           public float result = f;
        };
    }

    protected static Object makeObj(final int i) {
        return new Object() {
            public int result = i;
        };
    }

    protected static Object makeObj(final long l) {
        return new Object() {
           public long result = l;
        };
    }

    protected static Object makeObj(final short s) {
        return new Object() {
           public short result = s;
        };
    }
}
