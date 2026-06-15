import React, { useState } from 'react';
import {
  Platform,
  TouchableOpacity,
  type NativeSyntheticEvent,
  type StyleProp,
  type TargetedEvent,
  type TouchableOpacityProps,
  type ViewStyle,
} from 'react-native';
const isTV = Platform.isTV;

type FocusableProps = TouchableOpacityProps & {
  /** Style merged in while the element holds D-pad/remote focus (TV only). */
  focusStyle?: StyleProp<ViewStyle>;
};

const defaultFocusStyle: ViewStyle = {
  backgroundColor: 'rgba(31, 111, 235, 0.18)',
  borderRadius: 8,
};

/**
 * Drop-in replacement for {@link TouchableOpacity} that also renders a visible
 * focus highlight when navigated to with a TV remote / D-pad.
 */
export function Focusable({
  focusStyle,
  style,
  onFocus,
  onBlur,
  children,
  ...rest
}: FocusableProps) {
  const [focused, setFocused] = useState(false);

  const handleFocus = (e: NativeSyntheticEvent<TargetedEvent>) => {
    setFocused(true);
    onFocus?.(e);
  };
  const handleBlur = (e: NativeSyntheticEvent<TargetedEvent>) => {
    setFocused(false);
    onBlur?.(e);
  };

  return (
    <TouchableOpacity
      {...rest}
      style={[style, isTV && focused && (focusStyle ?? defaultFocusStyle)]}
      onFocus={handleFocus}
      onBlur={handleBlur}
    >
      {children}
    </TouchableOpacity>
  );
}
