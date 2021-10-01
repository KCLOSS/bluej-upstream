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
package bluej.extensions;

/**
 * This exception will be thrown when a reference to a class is no longer valid. 
 * The most likely reason is that the user has deleted the class
 * using the GUI.
 * 
 * @version $Id: ClassNotFoundException.java 6163 2009-02-19 18:09:55Z polle $
 */

/*
 * Author: Damiano Bolla, University of Kent at Canterbury, 2003
 */
public class ClassNotFoundException extends ExtensionException 
{
  ClassNotFoundException (String reason) {
      super (reason);
  }
}
