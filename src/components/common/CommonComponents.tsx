import React, { ReactNode, ButtonHTMLAttributes, useState, useEffect } from 'react';

// These are placeholder common components. Styling will heavily depend on Tailwind CSS setup.
// The classNames like 'retro-button', 'retro-input' are conceptual and would be defined
// in a global CSS or via Tailwind's @apply directive once Tailwind is working.

// Button variants
type ButtonVariant = 'primary' | 'secondary' | 'danger';

// RetroButton Component
interface RetroButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  children: ReactNode;
}

export const RetroButton: React.FC<RetroButtonProps> = ({ 
  variant = 'primary', 
  children, 
  className = '',
  ...props 
}) => {
  // Determine variant-specific styles
  let variantClasses = '';
  
  switch (variant) {
    case 'primary':
      variantClasses = 'bg-retro-green text-retro-black border-retro-green hover:bg-retro-green/80';
      break;
    case 'secondary':
      variantClasses = 'bg-retro-dark border-retro-green text-retro-green hover:bg-retro-dark/80';
      break;
    case 'danger':
      variantClasses = 'bg-retro-red text-retro-black border-retro-red hover:bg-retro-red/80';
      break;
  }
  
  return (
    <button
      className={`px-4 py-2 border-2 transition-all font-bold focus:outline-none focus:ring-2 focus:ring-retro-green/50 ${variantClasses} ${className}`}
      {...props}
    >
      {children}
    </button>
  );
};

// RetroLoadingBar Component
interface RetroLoadingBarProps {
  className?: string;
}

export const RetroLoadingBar: React.FC<RetroLoadingBarProps> = ({ className = '' }) => {
  return (
    <div className={`w-full h-4 border-2 border-retro-green bg-retro-black p-0.5 ${className}`}>
      <div className="h-full w-full bg-retro-green animate-pulse-green relative overflow-hidden">
        <div className="absolute inset-0 bg-retro-black opacity-70 animate-scan-line"></div>
      </div>
    </div>
  );
};

// RetroInput Component
interface RetroInputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
}

export const RetroInput: React.FC<RetroInputProps> = ({ 
  label, 
  className = '', 
  ...props 
}) => {
  return (
    <div className="w-full">
      {label && <label className="block mb-1 text-retro-green">{label}</label>}
      <input
        className={`w-full bg-retro-black border-2 border-retro-green text-retro-green px-3 py-2 focus:outline-none focus:ring-2 focus:ring-retro-green/50 ${className}`}
        {...props}
      />
    </div>
  );
};

// RetroSelect Component
interface SelectOption {
  value: string;
  label: string;
}

interface RetroSelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  options: SelectOption[];
}

export const RetroSelect: React.FC<RetroSelectProps> = ({ 
  options, 
  className = '', 
  ...props 
}) => {
  return (
    <select
      className={`w-full bg-retro-black border-2 border-retro-green text-retro-green px-3 py-2 focus:outline-none focus:ring-2 focus:ring-retro-green/50 ${className}`}
      {...props}
    >
      {options.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  );
};

// RetroCheckbox Component
interface RetroCheckboxProps extends React.InputHTMLAttributes<HTMLInputElement> {
  id: string;
  label: string;
}

export const RetroCheckbox: React.FC<RetroCheckboxProps> = ({ 
  id, 
  label, 
  className = '', 
  ...props 
}) => {
  return (
    <div className="flex items-center">
      <input
        id={id}
        type="checkbox"
        className={`w-4 h-4 border-2 border-retro-green bg-retro-black text-retro-green focus:ring-retro-green ${className}`}
        {...props}
      />
      <label htmlFor={id} className="ml-2 text-retro-green cursor-pointer">
        {label}
      </label>
    </div>
  );
};

// RetroModal Component
interface RetroModalProps {
  isOpen: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
}

export const RetroModal: React.FC<RetroModalProps> = ({ 
  isOpen, 
  onClose, 
  title, 
  children 
}) => {
  const [isAnimating, setIsAnimating] = useState(false);
  
  useEffect(() => {
    if (isOpen) {
      setIsAnimating(true);
    }
  }, [isOpen]);
  
  if (!isOpen && !isAnimating) return null;
  
  const handleAnimationEnd = () => {
    if (!isOpen) {
      setIsAnimating(false);
    }
  };
  
  return (
    <div 
      className={`fixed inset-0 z-50 flex items-center justify-center p-4 ${isOpen ? 'bg-black/70' : 'bg-transparent pointer-events-none'} transition-all duration-300`}
      onClick={onClose}
    >
      <div 
        className={`w-full max-w-lg bg-retro-dark border-2 border-retro-green text-retro-green transition-all duration-300 ${isOpen ? 'scale-100 opacity-100' : 'scale-95 opacity-0'}`}
        onClick={(e) => e.stopPropagation()}
        onAnimationEnd={handleAnimationEnd}
      >
        <div className="border-b-2 border-retro-green p-4 flex justify-between items-center">
          <h2 className="text-lg font-bold tracking-wider">{title}</h2>
          <button 
            onClick={onClose}
            className="text-retro-green hover:text-retro-red focus:outline-none"
          >
            [ X ]
          </button>
        </div>
        <div className="overflow-auto">
          {children}
        </div>
      </div>
    </div>
  );
};

// RetroCard Component
interface RetroCardProps {
  title?: string;
  children: ReactNode;
  className?: string;
}

export const RetroCard: React.FC<RetroCardProps> = ({ 
  title, 
  children, 
  className = ''
}) => {
  return (
    <div className={`retro-card ${className}`}>
      {title && (
        <div className="border-b border-retro-green p-2">
          <h3 className="font-bold text-retro-green">{title}</h3>
        </div>
      )}
      <div className="p-3">
        {children}
      </div>
    </div>
  );
};

// Example usage (not to be included in this file, but for demonstration)
/*
const App: React.FC = () => {
  const [isModalOpen, setIsModalOpen] = useState(false);
  return (
    <div>
      <RetroButton onClick={() => console.log('Clicked!')}>Primary Button</RetroButton>
      <RetroInput type="text" placeholder="Enter text..." />
      <RetroButton onClick={() => setIsModalOpen(true)}>Open Modal</RetroButton>
      <RetroModal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)} title="Sample Modal">
        <p>This is the content of the modal.</p>
        <RetroButton onClick={() => setIsModalOpen(false)} className="mt-4">Close</RetroButton>
      </RetroModal>
    </div>
  );
};
*/

