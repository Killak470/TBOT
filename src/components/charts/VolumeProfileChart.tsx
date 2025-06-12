import React from 'react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, TooltipProps } from 'recharts';

interface VolumeProfileData {
  price: number;
  volume: number;
  type: 'BUY' | 'SELL';
  fill?: string;
}

interface VolumeProfileProps {
  data: VolumeProfileData[];
}

const VolumeProfileChart: React.FC<VolumeProfileProps> = ({ data }) => {
  const CustomTooltip: React.FC<TooltipProps<number, string>> = ({ active, payload }) => {
    if (active && payload && payload.length) {
      const dataItem = payload[0].payload as VolumeProfileData;
      return (
        <div className="bg-gray-800 text-white p-2 rounded shadow-lg">
          <p className="font-semibold">Price: ${dataItem.price.toFixed(2)}</p>
          <p>Volume: {dataItem.volume}</p>
          <p>Type: <span className={dataItem.type === 'BUY' ? 'text-green-500' : 'text-red-500'}>{dataItem.type}</span></p>
        </div>
      );
    }
    return null;
  };

  const coloredData = data.map(item => ({
    ...item,
    fill: item.type === 'BUY' ? '#10B981' : '#EF4444',
  }));

  return (
    <ResponsiveContainer width="100%" height={300}>
      <BarChart data={coloredData} layout="vertical">
        <XAxis type="number" />
        <YAxis dataKey="price" type="category" width={80} />
        <Tooltip content={<CustomTooltip />} />
        <Bar
          dataKey="volume"
          opacity={0.8}
        />
      </BarChart>
    </ResponsiveContainer>
  );
};

export default VolumeProfileChart; 