/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */
package ai.grakn.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import org.apache.commons.lang.StringEscapeUtils;

/**
 * <p>
 * Some helper methods in dealing with strings in the context of GRAKN.
 * </p>
 * 
 * @author borislav
 *
 */
public class StringUtil {

    /**
     * @param string the string to unescape
     * @return the unescaped string, replacing any backslash escapes with the real characters
     */
    public static String unescapeString(String string) {
        return StringEscapeUtils.unescapeJavaScript(string);
    }

    /**
     * @param string the string to escape
     * @return the escaped string, replacing any escapable characters with backslashes
     */
    public static String escapeString(String string) {
        return StringEscapeUtils.escapeJavaScript(string);
    }

    /**
     * @param string a string to quote and escape
     * @return a string, surrounded with double quotes and escaped
     */
    public static String quoteString(String string) {
        return "\"" + StringUtil.escapeString(string) + "\"";
    }

    /**
     * @param value a value in the graph
     * @return the string representation of the value (using quotes if it is already a string)
     */
    public static String valueToString(Object value) {
        if (value instanceof String) {
            return quoteString((String) value);
        } else if (value instanceof Double) {
            DecimalFormat df = new DecimalFormat("#", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
            df.setMinimumFractionDigits(1);
            df.setMaximumFractionDigits(12);
            df.setMinimumIntegerDigits(1);
            return df.format(value);
        } else {
            return value.toString();
        }
    }

}
