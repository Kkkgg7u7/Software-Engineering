package com.werewolf.room.dto;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;

/** Reject fractional/string/boolean counts rather than silently coercing them. */
public class RoleCountDeserializer extends JsonDeserializer<Integer> {
    @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT))
            throw JsonMappingException.from(parser,"身份人数必须为整数");
        return parser.getIntValue();
    }
}
