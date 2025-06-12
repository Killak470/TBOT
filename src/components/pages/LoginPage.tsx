import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import useAppStore from '../../store/useAppStore';
import { RetroButton, RetroInput } from '../common/CommonComponents';

// Define the type for the state from AppStore
interface AppState {
  login: (userData: { id: string; username: string }, token: string) => void;
  // Add other properties as needed
}

const LoginPage: React.FC = () => {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const login = useAppStore((state: AppState) => state.login);
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    // Placeholder: Call backend login API
    console.log('Attempting login with:', { username, password });
    // Simulate successful login
    // In a real app, you would get user data and token from the backend
    const mockUserData = { id: 'user123', username: username };
    const mockToken = 'mock-jwt-token';
    login(mockUserData, mockToken);
    alert('Login Successful (Placeholder)!');
    navigate('/'); // Redirect to dashboard after login
  };

  return (
    <div className="p-4 retro-text flex flex-col items-center justify-center min-h-screen">
      <div className="retro-card p-8 w-full max-w-md">
        <h1 className="text-2xl font-bold mb-6 text-center retro-header">Login</h1>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="username" className="block mb-1 retro-subheader">Username:</label>
            <RetroInput 
              type="text" 
              id="username" 
              value={username} 
              onChange={(e) => setUsername(e.target.value)} 
              required 
            />
          </div>
          <div>
            <label htmlFor="password" className="block mb-1 retro-subheader">Password:</label>
            <RetroInput 
              type="password" 
              id="password" 
              value={password} 
              onChange={(e) => setPassword(e.target.value)} 
              required 
            />
          </div>
          <RetroButton type="submit" className="w-full bg-green-600 hover:bg-green-500">
            Login
          </RetroButton>
        </form>
        <p className="mt-4 text-center text-sm">
          Don't have an account? <a href="/register" className="text-retro-accent hover:underline">Register here</a>
        </p>
      </div>
    </div>
  );
};

export default LoginPage;

